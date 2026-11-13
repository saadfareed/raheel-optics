-- Preserve optical payloads in the compatibility order endpoint. The admission
-- wrapper stays in place; this function must never be callable by clients.
begin;
alter table public.todo_history drop constraint todo_history_action_check;
alter table public.todo_history add constraint todo_history_action_check check(action in ('complete','delete','reopen','edit'));
create or replace function public.apply_operation_unchecked(payload jsonb) returns jsonb
language plpgsql security definer set search_path=public,pg_temp as $$
declare
  owner uuid := auth.uid(); op uuid := (payload->>'id')::uuid;
  task uuid := (payload->'todo'->>'id')::uuid;
  act text := payload->>'action'; previous uuid := nullif(payload->>'previousOperation','')::uuid;
  expected bigint := (payload->>'baseRevision')::bigint;
  current_task public.todos%rowtype; receipt public.operation_receipts%rowtype;
  old_status text := 'absent'; new_status text;
  account_rev bigint; task_rev bigint; result jsonb; task_body jsonb;
  event_time bigint := (payload->>'occurredAt')::bigint;
  optical jsonb; field record; paid numeric; total numeric; history_limit bigint; history_usage bigint;
begin
  if owner is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if op is null or task is null or expected is null or act is null or act not in ('create','edit','complete','delete','reopen') then
    raise exception 'Invalid operation';
  end if;
  if jsonb_typeof(payload->'todo') is distinct from 'object'
    or jsonb_typeof(payload->'todo'->'title') is distinct from 'string'
    or (payload->'todo' ? 'notes' and jsonb_typeof(payload->'todo'->'notes') is distinct from 'string')
    or (payload->'todo' ? 'priority' and jsonb_typeof(payload->'todo'->'priority') is distinct from 'string')
    or event_time is null or event_time not between 0 and 253402300799999 then
    raise exception 'Invalid todo payload';
  end if;
  if exists (
    select 1 from jsonb_each(payload->'todo') timing
    where timing.key in ('dueAt','reminderAt') and timing.value<>'null'::jsonb and
      (jsonb_typeof(timing.value)<>'number' or timing.value::text !~ '^-?[0-9]+$')
  ) then raise exception 'Invalid todo time'; end if;
  if exists (
    select 1 from jsonb_each(payload->'todo') timing
    where timing.key in ('dueAt','reminderAt') and timing.value<>'null'::jsonb and
      (timing.value::text)::numeric not between -253402300799999 and 253402300799999
  ) then raise exception 'Invalid todo time'; end if;
  if length(trim(coalesce(payload->'todo'->>'title',''))) not between 1 and 160
    or length(coalesce(payload->'todo'->>'notes',''))>4000
    or coalesce(payload->'todo'->>'priority','Medium') not in ('Low','Medium','High') then
    raise exception 'Invalid todo';
  end if;
  -- This per-account lock orders mutations and serializes counter updates/idempotent retries.
  insert into public.user_totals(id) values(owner) on conflict do nothing;
  select revision into account_rev from public.user_totals where id=owner for update;
  select * into receipt from public.operation_receipts where id=op;
  if found then
    if receipt.user_id<>owner then raise exception 'Operation not owned' using errcode='42501'; end if;
    return receipt.result;
  end if;
  select * into current_task from public.todos where id=task for update;
  if found then
    if current_task.user_id<>owner then raise exception 'Todo not owned' using errcode='42501'; end if;
    old_status := current_task.status;
  end if;
  if previous is not null then
    select * into receipt from public.operation_receipts where id=previous and user_id=owner and todo_id=task;
    if found and (receipt.result->>'accepted')::boolean then expected:=receipt.todo_revision;
    else expected:=-1; end if;
  end if;
  new_status := case act when 'complete' then 'completed' when 'delete' then 'deleted' when 'edit' then old_status else 'active' end;
  if (old_status='absent' and (act<>'create' or expected<>0))
     or (old_status<>'absent' and (act='create' or current_task.revision<>expected))
     or old_status='deleted'
     or (act='complete' and old_status<>'active')
     or (act='edit' and old_status not in ('active','completed'))
     or (act='reopen' and old_status<>'completed') then
    result:=jsonb_build_object('accepted',false,'accountRevision',account_rev,'todoRevision',coalesce(current_task.revision,0),
      'message','This todo changed on another device. Keep the remote version or save your version as a new todo.');
    insert into public.operation_receipts(id,user_id,todo_id,result) values(op,owner,task,result);
    return result;
  end if;
  -- Retain old optical values when a legacy client omits them, but accept only
  -- known keys. Never merge arbitrary client-provided revision/status fields.
  select coalesce(jsonb_object_agg(key,value),'{}'::jsonb) into optical
    from jsonb_each(coalesce(current_task.body,'{}'::jsonb) || payload->'todo')
    where key in ('customerMobile','normalizedMobile','customerId','orderNumber','prescription','frame','lens','amounts','payments','orderStatus','orderedAt','deliveredAt');
  if optical ? 'orderStatus' and optical->>'orderStatus' not in ('ACTIVE','READY_FOR_PICKUP','COMPLETE','CANCEL') then raise exception 'Invalid order status'; end if;
  for field in select key,value from jsonb_each(optical) loop
    if field.key in ('prescription','frame','lens','amounts') and jsonb_typeof(field.value)<>'object' then raise exception 'Invalid optical object'; end if;
    if field.key in ('customerMobile','normalizedMobile','orderNumber') and (jsonb_typeof(field.value)<>'string' or length(field.value #>> '{}')>160) then raise exception 'Invalid optical text'; end if;
    if field.key in ('orderedAt','deliveredAt') and field.value<>'null'::jsonb and (jsonb_typeof(field.value)<>'number' or field.value::text !~ '^[0-9]{1,15}$') then raise exception 'Invalid optical date'; end if;
  end loop;
  if optical ? 'normalizedMobile' and optical->>'normalizedMobile'<>'' and optical->>'normalizedMobile' !~ '^\+923[0-9]{9}$' then raise exception 'Invalid Pakistani mobile'; end if;
  if optical ? 'customerId' and optical->'customerId'<>'null'::jsonb then perform (optical->>'customerId')::uuid; end if;
  if optical ? 'amounts' then
    for field in select key,value from jsonb_each(optical->'amounts') loop
      if field.key not in ('framePaisa','lensPaisa','extrasPaisa','discountPaisa','taxPaisa') or jsonb_typeof(field.value)<>'number' or field.value::text !~ '^[0-9]{1,15}$' then raise exception 'Invalid order amount'; end if;
    end loop;
  end if;
  if optical ? 'payments' then
    if jsonb_typeof(optical->'payments')<>'array' then raise exception 'Invalid payments'; end if;
    if jsonb_array_length(optical->'payments')>100 then raise exception 'Too many payment entries'; end if;
    for field in select value from jsonb_array_elements(optical->'payments') loop
      if jsonb_typeof(field.value)<>'object' or coalesce(field.value->>'amountPaisa','') !~ '^-?[0-9]{1,15}$' or coalesce(field.value->>'receivedAt','') !~ '^[0-9]{1,15}$' then raise exception 'Invalid payment'; end if;
    end loop;
    select coalesce(sum((value->>'amountPaisa')::numeric),0) into paid from jsonb_array_elements(optical->'payments');
    total:=coalesce((optical->'amounts'->>'framePaisa')::numeric,0)+coalesce((optical->'amounts'->>'lensPaisa')::numeric,0)+coalesce((optical->'amounts'->>'extrasPaisa')::numeric,0)+coalesce((optical->'amounts'->>'taxPaisa')::numeric,0)-coalesce((optical->'amounts'->>'discountPaisa')::numeric,0);
    if paid<0 or paid>greatest(0,total) then raise exception 'Payment exceeds order total or refund exceeds paid amount'; end if;
  end if;
  if optical ? 'orderStatus' then
    optical:=optical || jsonb_build_object('orderStatus',case when new_status='completed' then 'COMPLETE' when new_status='deleted' then 'CANCEL' when optical->>'orderStatus'='READY_FOR_PICKUP' then 'READY_FOR_PICKUP' else 'ACTIVE' end);
  end if;
  if act='edit' and old_status='completed' then
    select coalesce(o.max_history_events,p.max_history_events),coalesce(a.history_events,0) into history_limit,history_usage
      from public.resource_policy p left join public.account_resource_overrides o on o.user_id=owner left join public.mutation_admission a on a.user_id=owner where p.id=true;
    if history_usage>=history_limit then raise sqlstate 'PT429' using message='Account history limit reached. Local work is retained.',detail=jsonb_build_object('category','history_quota','limit',history_limit,'usage',history_usage)::text; end if;
  end if;
  task_rev:=coalesce(current_task.revision,0)+1;
  account_rev:=account_rev+1;
  task_body:=jsonb_build_object('id',task,'title',payload->'todo'->'title',
     'notes',coalesce(payload->'todo'->'notes','""'::jsonb),'priority',coalesce(payload->'todo'->'priority','"Medium"'::jsonb),
     'dueAt',payload->'todo'->'dueAt','reminderAt',payload->'todo'->'reminderAt') || jsonb_build_object('status',new_status,'revision',task_rev,
     'updatedAt',event_time,'completedAt',case when act='complete' then to_jsonb(event_time) when act in ('delete','edit') then current_task.body->'completedAt' else 'null'::jsonb end) || optical;
  if new_status<>'active' or act='reopen' then task_body:=task_body||jsonb_build_object('reminderAt',null); end if;
  insert into public.todos(id,user_id,body,status,revision) values(task,owner,task_body,new_status,task_rev)
    on conflict(id) do update set body=excluded.body,status=excluded.status,revision=excluded.revision;
  if new_status='active' then
    insert into public.active_todos(id,user_id,body,revision) values(task,owner,task_body::text,task_rev)
      on conflict(id) do update set body=excluded.body,revision=excluded.revision;
  else delete from public.active_todos where id=task; end if;
  update public.user_totals set
    active=active+(case when new_status='active' then 1 else 0 end)-(case when old_status='active' then 1 else 0 end),
    completed=completed+(case when new_status='completed' then 1 else 0 end)-(case when old_status='completed' then 1 else 0 end),
    deleted=deleted+(case when new_status='deleted' then 1 else 0 end)-(case when old_status='deleted' then 1 else 0 end),
    revision=account_rev where id=owner;
  if act in ('complete','delete','reopen') or (act='edit' and new_status='completed') then
    insert into public.todo_history(id,user_id,todo_id,action,occurred_at,body)
      values(op,owner,task,act,event_time,jsonb_build_object('id',op,'todo',task_body,'action',act,'occurredAt',event_time,'pending',false));
  end if;
  result:=jsonb_build_object('accepted',true,'accountRevision',account_rev,'todoRevision',task_rev,'message','');
  insert into public.operation_receipts(id,user_id,todo_id,todo_revision,result) values(op,owner,task,task_rev,result);
  return result;
end $$;
revoke all on function public.apply_operation_unchecked(jsonb) from public,anon,authenticated;
create or replace function public.optical_compatibility_version() returns integer
language sql stable security invoker set search_path=public,pg_temp as $$select 1$$;
revoke all on function public.optical_compatibility_version() from public,anon;
grant execute on function public.optical_compatibility_version() to authenticated;
notify pgrst, 'reload schema';

commit;

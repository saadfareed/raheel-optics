-- Validate server payloads and accept default fields from legacy clients.
begin;
create or replace function public.apply_operation(payload jsonb) returns jsonb
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
    select 1 from jsonb_each(payload->'todo') field
    where field.key in ('dueAt','reminderAt') and field.value<>'null'::jsonb and
      (jsonb_typeof(field.value)<>'number' or field.value::text !~ '^-?[0-9]+$')
  ) then raise exception 'Invalid todo time'; end if;
  if exists (
    select 1 from jsonb_each(payload->'todo') field
    where field.key in ('dueAt','reminderAt') and field.value<>'null'::jsonb and
      (field.value::text)::numeric not between -253402300799999 and 253402300799999
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
  new_status := case act when 'complete' then 'completed' when 'delete' then 'deleted' else 'active' end;
  if (old_status='absent' and (act<>'create' or expected<>0))
     or (old_status<>'absent' and (act='create' or current_task.revision<>expected))
     or old_status='deleted'
     or (act in ('edit','complete') and old_status<>'active')
     or (act='reopen' and old_status<>'completed') then
    result:=jsonb_build_object('accepted',false,'accountRevision',account_rev,'todoRevision',coalesce(current_task.revision,0),
      'message','This todo changed on another device. Keep the remote version or save your version as a new todo.');
    insert into public.operation_receipts(id,user_id,todo_id,result) values(op,owner,task,result);
    return result;
  end if;
  task_rev:=coalesce(current_task.revision,0)+1;
  account_rev:=account_rev+1;
  task_body:=jsonb_build_object('id',task,'title',payload->'todo'->'title',
     'notes',coalesce(payload->'todo'->'notes','""'::jsonb),'priority',coalesce(payload->'todo'->'priority','"Medium"'::jsonb),
     'dueAt',payload->'todo'->'dueAt','reminderAt',payload->'todo'->'reminderAt') || jsonb_build_object('status',new_status,'revision',task_rev,
     'updatedAt',event_time,'completedAt',case when act='complete' then to_jsonb(event_time) when act='delete' then current_task.body->'completedAt' else 'null'::jsonb end);
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
  if act in ('complete','delete','reopen') then
    insert into public.todo_history(id,user_id,todo_id,action,occurred_at,body)
      values(op,owner,task,act,event_time,jsonb_build_object('id',op,'todo',task_body,'action',act,'occurredAt',event_time,'pending',false));
  end if;
  result:=jsonb_build_object('accepted',true,'accountRevision',account_rev,'todoRevision',task_rev,'message','');
  insert into public.operation_receipts(id,user_id,todo_id,todo_revision,result) values(op,owner,task,task_rev,result);
  return result;
end $$;
revoke all on function public.apply_operation(jsonb) from public,anon;
grant execute on function public.apply_operation(jsonb) to authenticated;

commit;

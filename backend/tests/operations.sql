\set ON_ERROR_STOP on
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',true);
do $$
declare
  task uuid := 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
  first uuid := 'aaaaaaaa-1111-1111-1111-111111111111';
  second uuid := 'aaaaaaaa-2222-2222-2222-222222222222';
  third uuid := 'aaaaaaaa-3333-3333-3333-333333333333';
  p jsonb; r jsonb; count_events integer;
begin
  p:=jsonb_build_object('id',first,'action','create','baseRevision',0,'occurredAt',1000,
    'todo',jsonb_build_object('id',task,'title','Offline task','notes','History payload','priority','High','status','active','dueAt',3000,'reminderAt',2000));
  r:=public.apply_operation(p);
  if not (r->>'accepted')::boolean then raise exception 'create rejected'; end if;
  if public.apply_operation(p)<>r then raise exception 'replay not idempotent'; end if;
  if (select active from public.user_totals)<>1 then raise exception 'duplicate create count'; end if;
  p:=p||jsonb_build_object('id',second,'action','complete','previousOperation',first,'occurredAt',2500);
  r:=public.apply_operation(p);
  if not (r->>'accepted')::boolean then raise exception 'offline chained completion rejected'; end if;
  perform public.apply_operation(p);
  if exists(select 1 from public.active_todos) then raise exception 'terminal task retained active'; end if;
  if (select completed from public.user_totals)<>1 then raise exception 'completion count incorrect'; end if;
  if (select count(*) from public.todo_history)<>1 then raise exception 'duplicate history'; end if;
  if (select body->'todo'->>'title' from public.todo_history)<>'Offline task' then raise exception 'history snapshot lost'; end if;
  p:=p||jsonb_build_object('id',third,'action','delete','previousOperation',second,'occurredAt',4000);
  r:=public.apply_operation(p);
  if not (r->>'accepted')::boolean then raise exception 'chained delete rejected'; end if;
  if (select deleted from public.user_totals)<>1 or (select completed from public.user_totals)<>0 then raise exception 'delete totals incorrect'; end if;
  if (select count(*) from public.todo_history)<>2 then raise exception 'completion history erased by delete'; end if;
  p:=p||jsonb_build_object('id','aaaaaaaa-4444-4444-4444-444444444444','action','edit','previousOperation',null,'baseRevision',1);
  r:=public.apply_operation(p);
  if (r->>'accepted')::boolean then raise exception 'stale edit resurrected deleted task'; end if;
  if exists(select 1 from public.active_todos) then raise exception 'resurrection'; end if;
  if (select count(*) from public.history_page())<>2 then raise exception 'history RPC omitted data'; end if;
  if (select count(*) from public.history_page(4000,third,100))<>1 then raise exception 'keyset pagination incorrect'; end if;
end $$;

-- Stale competing edits and completion/reopen transitions retain the canonical winner.
do $$
declare
  p jsonb; result jsonb;
  task uuid:='cccccccc-cccc-cccc-cccc-cccccccccccc';
begin
  p:=jsonb_build_object('id','cccccccc-1111-1111-1111-111111111111','action','create','baseRevision',0,'occurredAt',1000,
    'todo',jsonb_build_object('id',task,'title','Concurrent task','priority','Medium','dueAt',9000));
  perform public.apply_operation(p);
  p:=p||jsonb_build_object('id','cccccccc-2222-2222-2222-222222222222','action','edit','baseRevision',1,
    'todo',p->'todo'||jsonb_build_object('title','First edit'));
  result:=public.apply_operation(p);
  if not (result->>'accepted')::boolean then raise exception 'first edit failed'; end if;
  result:=public.apply_operation(p||jsonb_build_object('id','cccccccc-3333-3333-3333-333333333333','todo',p->'todo'||jsonb_build_object('title','Stale second edit')));
  if (result->>'accepted')::boolean then raise exception 'stale concurrent edit accepted'; end if;
  if (select body->>'title' from public.todos where id=task)<>'First edit' then raise exception 'canonical edit lost'; end if;
  p:=p||jsonb_build_object('id','cccccccc-4444-4444-4444-444444444444','action','complete','baseRevision',2,'occurredAt',8000);
  result:=public.apply_operation(p);
  if not (result->>'accepted')::boolean then raise exception 'completion failed'; end if;
  if (select body->'todo'->>'dueAt' from public.todo_history where todo_id=task)<>'9000' then raise exception 'deadline snapshot lost'; end if;
  p:=p||jsonb_build_object('id','cccccccc-5555-5555-5555-555555555555','action','reopen','baseRevision',3,'occurredAt',10000);
  result:=public.apply_operation(p);
  if not (result->>'accepted')::boolean then raise exception 'reopen failed'; end if;
  if not exists(select 1 from public.active_todos where id=task) then raise exception 'reopen missing from working set'; end if;
  if (select count(*) from public.todo_history where todo_id=task)<>2 then raise exception 'reopen erased completion'; end if;
  result:=public.apply_operation(p||jsonb_build_object('id','cccccccc-6666-6666-6666-666666666666','action','complete','baseRevision',2));
  if (result->>'accepted')::boolean then raise exception 'stale completion overwrote reopening'; end if;
end $$;

-- Different account cannot read or mutate another account's task, even using a guessed UUID.
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',true);
do $$
begin
  if exists(select 1 from public.todos) or exists(select 1 from public.todo_history) or exists(select 1 from public.user_totals) then raise exception 'RLS isolation failed'; end if;
  begin
    perform public.apply_operation(jsonb_build_object('id','bbbbbbbb-1111-1111-1111-111111111111','action','edit','baseRevision',1,'occurredAt',5000,
      'todo',jsonb_build_object('id','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','title','Attack','priority','Low')));
    raise exception 'cross-account update permitted';
  exception when insufficient_privilege then null; end;
  begin
    insert into public.active_todos(id,user_id,body,revision) values('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',auth.uid(),'{}',1);
    raise exception 'direct write permitted';
  exception when insufficient_privilege then null; end;
end $$;
rollback;
\echo 'PASS: idempotency, offline chaining, terminal retention, history, pagination, tombstones, stale edits, completion/reopen, immutable deadline snapshots and authorization'

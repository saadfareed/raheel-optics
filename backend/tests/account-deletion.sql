-- Hard deletion must cascade every account-owned application table.
begin;
do $$
declare target uuid := '11111111-1111-1111-1111-111111111111'; other uuid := '22222222-2222-2222-2222-222222222222'; item record; remaining bigint;
begin
  perform set_config('request.jwt.claim.sub',target::text,true);
  perform public.apply_operation(jsonb_build_object('id','aaaaaaaa-1111-1111-1111-111111111111','action','create','baseRevision',0,'occurredAt',1000,
    'todo',jsonb_build_object('id','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','title','Delete me','notes','Private','priority','High','status','active')));
  perform public.apply_operation(jsonb_build_object('id','aaaaaaaa-2222-2222-2222-222222222222','action','complete','baseRevision',1,'occurredAt',2000,
    'todo',jsonb_build_object('id','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','title','Delete me','notes','Private','priority','High','status','active')));
  if not exists(select 1 from public.todo_history where user_id=target) or not exists(select 1 from public.todo_sync where user_id=target) then
    raise exception 'Deletion fixtures missing'; end if;
  perform public.apply_operation(jsonb_build_object('id','bbbbbbbb-1111-1111-1111-111111111111','action','create','baseRevision',0,'occurredAt',1000,
    'todo',jsonb_build_object('id','bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb','title','Active owned task','priority','Medium','status','active')));
  perform set_config('request.jwt.claim.sub',other::text,true);
  perform public.apply_operation(jsonb_build_object('id','cccccccc-1111-1111-1111-111111111111','action','create','baseRevision',0,'occurredAt',1000,
    'todo',jsonb_build_object('id','cccccccc-cccc-cccc-cccc-cccccccccccc','title','Other account task','priority','Low','status','active')));
  delete from auth.users where id=target;
  for item in select table_name,column_name from information_schema.columns
    where table_schema='public' and table_name in ('todos','active_todos','user_totals','todo_history','operation_receipts','todo_sync','todo_history_latest','history_analytics_totals','mutation_admission','account_resource_overrides')
      and column_name in ('user_id','id') loop
    -- Only user_totals.id is an account identifier; other primary ids are task/event ids.
    if item.column_name='user_id' or item.table_name='user_totals' then
      execute format('select count(*) from public.%I where %I=$1',item.table_name,item.column_name) into remaining using target;
      if remaining<>0 then raise exception 'Account data survives in %',item.table_name; end if;
    end if;
  end loop;
  if not exists(select 1 from public.active_todos where user_id=other) then raise exception 'Another account data removed'; end if;
  if not exists(select 1 from auth.users where id=other) then raise exception 'Another account was removed'; end if;
end $$;
rollback;

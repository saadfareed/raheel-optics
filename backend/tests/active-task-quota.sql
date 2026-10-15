\set ON_ERROR_STOP on
begin;
update public.resource_policy set max_tasks=1,operations_per_minute=100,max_history_events=100,max_receipts=100;
set local role authenticated;
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',true);
do $$
declare first jsonb; second jsonb; completion jsonb; deletion jsonb; reopening jsonb; result jsonb; error_detail text;
begin
    if not public.account_resource_limits() @> jsonb_build_object('version',1,'taskScope','active','maxActiveTasks',1) then raise exception 'Quota contract incorrect'; end if;
    if public.sync_changes()->'resource_limits'<>public.account_resource_limits() then raise exception 'Sync omitted quota contract'; end if;
    first:=jsonb_build_object('id',gen_random_uuid(),'action','create','baseRevision',0,'occurredAt',1000,'todo',jsonb_build_object('id',gen_random_uuid(),'title','First'));
    second:=first||jsonb_build_object('id',gen_random_uuid(),'todo',jsonb_build_object('id',gen_random_uuid(),'title','Second'));
    result:=public.apply_operation(first);
    if not (result->>'accepted')::boolean then raise exception 'First rejected'; end if;
    begin perform public.apply_operation(second);raise exception 'CREATE_AT_CAP_ACCEPTED'; exception when sqlstate 'PT429' then get stacked diagnostics error_detail=pg_exception_detail; if error_detail::jsonb->>'category'<>'task_quota' then raise exception 'Quota category missing'; end if; end;
    if public.apply_operation(first)<>result then raise exception 'Replay at cap rejected'; end if;
    completion:=first||jsonb_build_object('id',gen_random_uuid(),'action','complete','previousOperation',first->>'id');
    result:=public.apply_operation(completion);
    if not (result->>'accepted')::boolean then raise exception 'Completion at cap rejected'; end if;
    result:=public.apply_operation(second);
    if not (result->>'accepted')::boolean then raise exception 'Completion failed to release slot'; end if;
    reopening:=first||jsonb_build_object('id',gen_random_uuid(),'action','reopen','previousOperation',completion->>'id');
    begin perform public.apply_operation(reopening);raise exception 'REOPEN_AT_CAP_ACCEPTED'; exception when sqlstate 'PT429' then get stacked diagnostics error_detail=pg_exception_detail; if error_detail::jsonb->>'category'<>'task_quota' then raise exception 'Quota category missing'; end if; end;
    deletion:=second||jsonb_build_object('id',gen_random_uuid(),'action','delete','previousOperation',second->>'id');
    result:=public.apply_operation(deletion);
    if not (result->>'accepted')::boolean then raise exception 'Deletion at cap rejected'; end if;
    result:=public.apply_operation(reopening);
    if not (result->>'accepted')::boolean then raise exception 'Deletion failed to release reopen slot'; end if;
    if (select active from public.user_totals where id=auth.uid())<>1 then raise exception 'Active count wrong'; end if;
    if (select deleted from public.user_totals where id=auth.uid())<>1 then raise exception 'Retained deletion lost'; end if;
    if (select count(*) from public.todo_history)<>3 then raise exception 'History lost'; end if;
    if has_function_privilege('anon','public.account_resource_limits()','execute') then raise exception 'Anonymous policy access'; end if;
end $$;
reset role;
rollback;
\echo 'PASS: active quota contract, create/reopen boundaries, completion/deletion slot release, history preservation'

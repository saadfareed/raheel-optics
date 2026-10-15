\set ON_ERROR_STOP on
begin;
update public.resource_policy set operations_per_minute=2,max_tasks=2,max_history_events=1;
set local role authenticated;
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',true);
do $$
declare first jsonb; second jsonb; third jsonb; result jsonb;
begin
    if has_function_privilege('authenticated','public.apply_operation_unchecked(jsonb)','execute') then raise exception 'Unchecked mutation exposed'; end if;
    if has_table_privilege('authenticated','public.resource_policy','update') then raise exception 'Policy writable'; end if;
    first:=jsonb_build_object('id',gen_random_uuid(),'action','create','baseRevision',0,'occurredAt',1000,
        'todo',jsonb_build_object('id',gen_random_uuid(),'title','First'));
    second:=first||jsonb_build_object('id',gen_random_uuid(),'todo',jsonb_build_object('id',gen_random_uuid(),'title','Second'));
    third:=first||jsonb_build_object('id',gen_random_uuid(),'todo',jsonb_build_object('id',gen_random_uuid(),'title','Third'));
    result:=public.apply_operations(jsonb_build_array(first,second,third));
    if jsonb_array_length(result)<>3 or result->2->>'error'<>'PT429' then raise exception 'Rate limit missing'; end if;
    if (result->2->>'details')::jsonb->>'category'<>'rate_limit' or ((result->2->>'details')::jsonb->>'retryAfterSeconds')::integer not between 1 and 60 then raise exception 'Rate retry contract missing'; end if;
    if (select count(*) from public.todos)<>2 then raise exception 'Accepted prefix missing'; end if;
    if public.apply_operation(first)<>result->0->'result' then raise exception 'Receipt replay throttled'; end if;
    begin
        perform public.apply_operation(third||jsonb_build_object('unused',repeat('x',33000)));
        raise exception 'OVERSIZE_ACCEPTED';
    exception when sqlstate 'PT413' then null;
    end;
    begin
        perform public.apply_operations((select jsonb_agg(first) from generate_series(1,26)));
        raise exception 'OVERSIZE_BATCH_ACCEPTED';
    exception when sqlstate 'PT413' then null;
    end;
end $$;
reset role;
update public.mutation_admission set window_start=now()-interval '2 minutes';
set local role authenticated;
do $$
declare payload jsonb;
begin
    payload:=jsonb_build_object('id',gen_random_uuid(),'action','create','baseRevision',0,'occurredAt',1000,
        'todo',jsonb_build_object('id',gen_random_uuid(),'title','Over quota'));
    begin
        perform public.apply_operation(payload);raise exception 'TASK_QUOTA_IGNORED';
    exception when sqlstate 'PT429' then null;
    end;
end $$;
reset role;
update public.resource_policy set operations_per_minute=20,max_tasks=20;
set local role authenticated;
do $$
declare payload jsonb; task jsonb; previous uuid; result jsonb; error_detail text;
begin
    select body into task from public.todos limit 1;
    payload:=jsonb_build_object('id',gen_random_uuid(),'action','complete','baseRevision',1,'occurredAt',2000,'todo',task);
    result:=public.apply_operation(payload);
    if not (result->>'accepted')::boolean then raise exception 'Completion rejected'; end if;
    previous:=(payload->>'id')::uuid;
    payload:=payload||jsonb_build_object('id',gen_random_uuid(),'action','reopen','previousOperation',previous);
    begin perform public.apply_operation(payload);raise exception 'HISTORY_QUOTA_IGNORED';
    exception when sqlstate 'PT429' then get stacked diagnostics error_detail=pg_exception_detail; if error_detail::jsonb->>'category'<>'history_quota' then raise exception 'History category missing'; end if;
    end;
end $$;
reset role;
do $$begin
    if (select history_events from public.mutation_admission where user_id='11111111-1111-1111-1111-111111111111')<>1 then raise exception 'History budget wrong'; end if;
end $$;
delete from public.todo_history;
do $$begin
    if (select history_events from public.mutation_admission where user_id='11111111-1111-1111-1111-111111111111')<>0 then raise exception 'History budget not released'; end if;
end $$;
update public.resource_policy set max_receipts=1;
set local role authenticated;
do $$begin
    begin
        perform public.apply_operation(jsonb_build_object('id',gen_random_uuid(),'action','create','baseRevision',0,'occurredAt',1000,'todo',jsonb_build_object('id',gen_random_uuid(),'title','Receipt quota')));
        raise exception 'RECEIPT_QUOTA_IGNORED';
    exception when sqlstate 'PT429' then null;
    end;
end $$;
reset role;
rollback;
\echo 'PASS: bounded batches, idempotent replay, rate/task/history admission, private grants'

begin;
set local role authenticated;
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',true);
do $$
declare op uuid:=gen_random_uuid(); task uuid:=gen_random_uuid(); first jsonb; second jsonb; result jsonb;
begin
    first:=jsonb_build_object('id',op,'action','create','baseRevision',0,'occurredAt',1000,
        'todo',jsonb_build_object('id',task,'title','Chain'));
    second:=first||jsonb_build_object('id',gen_random_uuid(),'action','complete','previousOperation',op);
    result:=public.apply_operations(jsonb_build_array(first,second));
    if not (result->1->'result'->>'accepted')::boolean then raise exception 'Ordered chain failed'; end if;
    if public.apply_operations(jsonb_build_array(first,second))<>result then raise exception 'Batch replay changed'; end if;
end $$;
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',true);
do $$begin
    if exists(select 1 from public.todos) or exists(select 1 from public.operation_receipts) then raise exception 'Cross-account read'; end if;
end $$;
reset role;
do $$begin
    if has_function_privilege('anon','public.apply_operations(jsonb)','execute') then raise exception 'Anonymous batch allowed'; end if;
end $$;
rollback;

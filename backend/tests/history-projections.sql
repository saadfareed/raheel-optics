begin;
set local role authenticated;
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',true);
do $$
declare task uuid:=gen_random_uuid(); op uuid; previous uuid; action text; payload jsonb;
    result jsonb; stamp bigint:=(extract(epoch from now())*1000)::bigint;
    old_time bigint:=stamp-100*86400000::bigint; expected jsonb; actual jsonb;
begin
    foreach action in array array['create','complete','reopen','complete','delete'] loop
        op:=gen_random_uuid();
        payload:=jsonb_build_object('id',op,'action',action,'baseRevision',0,'previousOperation',previous,
            'occurredAt',case when previous is null then stamp else old_time end,
            'todo',jsonb_build_object('id',task,'title','Projected','priority','High','dueAt',stamp));
        result:=public.apply_operation(payload);
        if not (result->>'accepted')::boolean then raise exception 'Projection lifecycle rejected: %', action; end if;
        -- Duplicate upload must never increment the derived totals again.
        perform public.apply_operation(payload);
        previous:=op;
    end loop;
    select body into expected from public.todo_history where todo_id=task
        order by (body->'todo'->>'revision')::bigint desc,occurred_at desc,id desc limit 1;
    select body into actual from public.todo_history_latest where todo_id=task;
    if actual is distinct from expected then raise exception 'Incremental history differs from ledger'; end if;
    result:=public.analytics_summary(7,'Asia/Karachi');
    if (result->>'withDeadline')::bigint<>2 or (result->>'onTime')::bigint<>2 then
        raise exception 'Lifetime counts lost old events or counted duplicate operations';
    end if;
    if (select sum(value::bigint) from jsonb_array_elements_text(result->'trend'))<>0 then
        raise exception 'Old lifetime events leaked into short trend window';
    end if;
    -- Verify timezone day boundaries against the ledger, with and without deadlines.
    for i in 1..2 loop
        task:=gen_random_uuid(); op:=gen_random_uuid();
        stamp:=(extract(epoch from ((current_timestamp at time zone 'Asia/Karachi')::date::timestamp at time zone 'Asia/Karachi'))*1000)::bigint
            + case when i=1 then 1 else -1 end;
        payload:=jsonb_build_object('id',op,'action','create','baseRevision',0,'occurredAt',stamp,
            'todo',jsonb_build_object('id',task,'title','Boundary','priority','Low'));
        perform public.apply_operation(payload);
        perform public.apply_operation(payload||jsonb_build_object('id',gen_random_uuid(),'action','complete','baseRevision',1,'previousOperation',op));
    end loop;
    result:=public.analytics_summary(7,'Asia/Karachi');
    if (result->'priorityTrends'->'Low'->>6)::bigint<>1 or (result->'priorityTrends'->'Low'->>5)::bigint<>1 then
        raise exception 'Timezone day boundary changed';
    end if;
    if (result->>'withDeadline')::bigint<>2 then raise exception 'No-deadline event changed deadline totals'; end if;
    begin
        delete from public.todo_history_latest;
        raise exception 'Client can write history projection';
    exception when insufficient_privilege then null;
    end;
    begin
        update public.history_analytics_totals set on_time=0;
        raise exception 'Client can write analytics totals';
    exception when insufficient_privilege then null;
    end;
end $$;
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',true);
do $$ begin
    if exists(select 1 from public.todo_history_latest) or exists(select 1 from public.history_analytics_totals) then
        raise exception 'Projection leaked across owners';
    end if;
end $$;
set local role anon;
do $$ begin
    begin
        perform * from public.todo_history_latest;
        raise exception 'Anonymous projection access';
    exception when insufficient_privilege then null;
    end;
end $$;
rollback;

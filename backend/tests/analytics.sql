\set ON_ERROR_STOP on
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',true);
do $$
declare p jsonb; task uuid; result jsonb; op uuid; stamp bigint:=(extract(epoch from now())*1000)::bigint;
begin
    for i in 1..2 loop
        task:=gen_random_uuid();op:=gen_random_uuid();
        p:=jsonb_build_object('id',op,'action','create','baseRevision',0,'occurredAt',stamp,
            'todo',jsonb_build_object('id',task,'title','Deadline','priority','Medium','dueAt',stamp+case when i=1 then 1000 else -1000 end));
        perform public.apply_operation(p);
        perform public.apply_operation(p||jsonb_build_object('id',gen_random_uuid(),'action','complete','baseRevision',1,'previousOperation',op));
    end loop;
    result:=public.analytics_summary(7,'Asia/Karachi');
    if (select sum(value::integer) from jsonb_array_elements_text(result->'trend'))<>2
        or (result->>'withDeadline')::integer<>2 or (result->>'onTime')::integer<>1 then raise exception 'analytics aggregates incorrect'; end if;
    if jsonb_array_length(result->'priorityTrends'->'High')<>7
        or (select sum(value::integer) from jsonb_array_elements_text(result->'priorityTrends'->'Medium'))<>2
        or (select sum(value::integer) from jsonb_array_elements_text(result->'priorityTrends'->'Low'))<>0 then
        raise exception 'priority analytics incorrect';
    end if;
    if jsonb_array_length(public.analytics_summary(30,'UTC')->'trend')<>30 then raise exception 'analytics window incorrect'; end if;
end $$;
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',true);
do $$ begin
    if (public.analytics_summary()->>'withDeadline')::integer<>0 or (select sum(value::integer) from jsonb_array_elements_text(public.analytics_summary()->'priorityTrends'->'Medium'))<>0 then raise exception 'analytics leaked across accounts'; end if;
end $$;
rollback;
\echo 'PASS: full-history analytics, timezones, deadlines and ownership'

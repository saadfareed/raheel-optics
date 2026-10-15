\set ON_ERROR_STOP on
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',true);
do $$
declare p jsonb; result jsonb; invalid jsonb;
begin
    p:=jsonb_build_object('id',gen_random_uuid(),'action','create','baseRevision',0,'occurredAt',1000,
        'todo',jsonb_build_object('id',gen_random_uuid(),'title','Legacy default priority'));
    result:=public.apply_operation(p);
    if not (result->>'accepted')::boolean then raise exception 'legacy payload rejected'; end if;
    if (select body->>'priority' from public.todos)<>'Medium' then raise exception 'default priority missing'; end if;
    foreach invalid in array array['{"dueAt":1.5}'::jsonb,'{"reminderAt":"bad"}'::jsonb,'{"notes":{}}'::jsonb,'{"dueAt":999999999999999999999}'::jsonb] loop
        begin
            perform public.apply_operation(p||jsonb_build_object('id',gen_random_uuid(),'todo',(p->'todo')||jsonb_build_object('id',gen_random_uuid())||invalid));
            raise exception 'INVALID_PAYLOAD_ACCEPTED';
        exception when others then
            if sqlerrm='INVALID_PAYLOAD_ACCEPTED' then raise; end if;
        end;
    end loop;
end $$;
rollback;
\echo 'PASS: legacy defaults and invalid payload rejection'

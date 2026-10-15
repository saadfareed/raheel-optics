\set ON_ERROR_STOP on
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',true);
do $$
declare ops jsonb; result jsonb; task uuid:=gen_random_uuid(); op uuid:=gen_random_uuid();
begin
    if (select prosecdef from pg_proc where oid='public.sync_exchange(jsonb,bigint,uuid,integer)'::regprocedure) then raise exception 'Exchange must use caller privileges'; end if;
    ops:=jsonb_build_array(jsonb_build_object('id',op,'action','create','baseRevision',0,'occurredAt',1000,'todo',jsonb_build_object('id',task,'title','Queued task')));
    result:=public.sync_exchange(ops);
    if jsonb_array_length(result->'receipts')<>1 or not (result->'receipts'->0->'result'->>'accepted')::boolean then raise exception 'Missing receipt'; end if;
    if not exists(select 1 from jsonb_array_elements(result->'changes') x where x->>'id'=task::text) then raise exception 'Upload not visible in same response'; end if;
    result:=public.sync_exchange(ops);
    if not (result->'receipts'->0->'result'->>'accepted')::boolean then raise exception 'Replay not idempotent'; end if;
    perform set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',true);
    result:=public.sync_exchange();
    if exists(select 1 from jsonb_array_elements(result->'changes') x where x->>'id'=task::text) then raise exception 'Cross-account leak'; end if;
    perform set_config('request.jwt.claim.sub','',true);
    begin perform public.sync_exchange();raise exception 'Missing identity accepted';exception when insufficient_privilege then null;end;
end $$;
reset role;
-- A fresh account has room for 100 operations under the normal rate policy.
insert into auth.users(id) values('33333333-3333-3333-3333-333333333333');
set local role authenticated;
select set_config('request.jwt.claim.sub','33333333-3333-3333-3333-333333333333',true);
do $$
declare ops jsonb; result jsonb; replay jsonb;
begin
    select jsonb_agg(jsonb_build_object('id',gen_random_uuid(),'action','create','baseRevision',0,'occurredAt',1000,
        'todo',jsonb_build_object('id',gen_random_uuid(),'title','Offline task '||i))) into ops from generate_series(1,100) i;
    result:=public.sync_exchange(ops);
    if jsonb_array_length(result->'receipts')<>100 or jsonb_array_length(result->'changes')<>100 then raise exception '100-task batch incomplete'; end if;
    replay:=public.sync_exchange(ops);
    if jsonb_array_length(replay->'receipts')<>100 or (replay->'totals'->>'active')::integer<>100 then raise exception 'Replay duplicated tasks'; end if;
    begin perform public.sync_exchange(ops||jsonb_build_array(ops->0));raise exception 'Oversized count accepted';exception when sqlstate 'PT413' then null;end;
end $$;
reset role;
insert into auth.users(id) values('44444444-4444-4444-4444-444444444444');
update public.resource_policy set operations_per_minute=25 where id=true;
set local role authenticated;
select set_config('request.jwt.claim.sub','44444444-4444-4444-4444-444444444444',true);
do $$
declare ops jsonb; result jsonb;
begin
    select jsonb_agg(jsonb_build_object('id',gen_random_uuid(),'action','create','baseRevision',0,'occurredAt',1000,
        'todo',jsonb_build_object('id',gen_random_uuid(),'title','Limited task '||i))) into ops from generate_series(1,100) i;
    result:=public.sync_exchange(ops);
    if jsonb_array_length(result->'receipts')<>26 or result->'receipts'->25->>'error'<>'PT429'
        or (result->'totals'->>'active')::integer<>25 then raise exception 'Admission did not stop at chunk boundary'; end if;
end $$;
reset role;
rollback;
\echo 'PASS: single exchange visibility, replay, caller privileges and account isolation'

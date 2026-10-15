\set ON_ERROR_STOP on
begin;
update public.resource_policy set max_receipts=1;
insert into public.account_resource_overrides(user_id,max_receipts) values('11111111-1111-1111-1111-111111111111',3);
set local role authenticated;
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',true);
do $$
declare op jsonb;
begin
    if (public.account_resource_limits()->>'maxReceipts')::integer<>3 then raise exception 'Override not returned'; end if;
    if has_table_privilege('authenticated','public.account_resource_overrides','select') then raise exception 'Override exposed'; end if;
    for i in 1..3 loop
        op:=jsonb_build_object('id',gen_random_uuid(),'action','create','baseRevision',0,'occurredAt',1000,'todo',jsonb_build_object('id',gen_random_uuid(),'title','Supported task'));
        if not (public.apply_operation(op)->>'accepted')::boolean then raise exception 'Override admission failed'; end if;
    end loop;
    begin perform public.apply_operation(op||jsonb_build_object('id',gen_random_uuid()));raise exception 'Receipt override exceeded'; exception when sqlstate 'PT429' then null; end;
end $$;
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',true);
do $$begin
    if (public.account_resource_limits()->>'maxReceipts')::integer<>1 then raise exception 'Cross-account override leaked'; end if;
end $$;
reset role;
rollback;
\echo 'PASS: private per-account quota increases without ledger pruning or cross-account policy leakage'

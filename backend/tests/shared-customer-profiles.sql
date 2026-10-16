-- Run only against an isolated local migration test database.
create function pg_temp.profile_op(op uuid,cid uuid,customer_name text,phone text,base bigint,sphere text) returns jsonb
language sql as $$ select jsonb_build_object('id',op,'action','customer_upsert','baseRevision',base,
 'payload',jsonb_build_object('type','customer_upsert','customer',jsonb_build_object('id',cid,'name',customer_name,
 'mobile',phone,'normalizedMobile',public.normalize_pk_mobile(phone),'notes','','previousMobiles','[]'::jsonb,
 'latestPrescription',jsonb_build_object('distanceRight',jsonb_build_object('sphere',sphere),'distanceLeft','{}'::jsonb)))); $$;
set role authenticated;
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',false);
do $$ declare op jsonb; result jsonb; replay jsonb; begin
 op:=pg_temp.profile_op('10000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001','Ayesha','03001234567',0,'-2.00');
 result:=public.upsert_optical_customer(op); replay:=public.upsert_optical_customer(op);
 if not (result->>'accepted')::boolean or replay<>result then raise exception 'Create/replay failed'; end if;
 if (select count(*) from public.optical_customers)<>1 then raise exception 'Replay duplicated customer'; end if;
end $$;
insert into public.optical_orders(id,user_id,customer_id,order_number,status,ordered_at,updated_at,prescription)
values('30000000-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111','20000000-0000-0000-0000-000000000001','PROFILE-1','active',100,100,'{"distanceRight":{"sphere":"-2.00"}}');
do $$ declare result jsonb; begin
 result:=public.upsert_optical_customer(pg_temp.profile_op('10000000-0000-0000-0000-000000000002','20000000-0000-0000-0000-000000000001','Ayesha Khan','03011234567',1,'-3.00'));
 if not (result->>'accepted')::boolean or result->'customer'->>'name'<>'Ayesha Khan'
   or not (result->'customer'->'previousMobiles' ? '+923001234567') then raise exception 'Edit/phone history failed'; end if;
 if (select prescription->'distanceRight'->>'sphere' from public.optical_orders where order_number='PROFILE-1')<>'-2.00' then raise exception 'Historical prescription changed'; end if;
 result:=public.upsert_optical_customer(pg_temp.profile_op('10000000-0000-0000-0000-000000000003','20000000-0000-0000-0000-000000000001','Stale edit','03011234567',1,'-9.00'));
 if (result->>'accepted')::boolean then raise exception 'Stale revision accepted'; end if;
 result:=public.upsert_optical_customer(pg_temp.profile_op('10000000-0000-0000-0000-000000000004','20000000-0000-0000-0000-000000000002','Duplicate','03001234567',0,'0'));
 if (result->>'accepted')::boolean then raise exception 'Phone alias collision accepted'; end if;
 if (select count(*) from public.optical_customer_page())<>1 then raise exception 'Customer page failed'; end if;
end $$;
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false);
do $$ begin
 if exists(select 1 from public.optical_customer_page()) then raise exception 'Cross-owner profile leak'; end if;
 begin
   perform public.upsert_optical_customer(pg_temp.profile_op('10000000-0000-0000-0000-000000000005','20000000-0000-0000-0000-000000000001','Takeover','03011234567',2,'0'));
   raise exception 'Cross-owner mutation allowed';
 exception when insufficient_privilege then null; end;
end $$;
reset role;
do $$ begin
 if has_function_privilege('anon','public.upsert_optical_customer(jsonb)','execute') then raise exception 'Anonymous mutation allowed'; end if;
end $$;

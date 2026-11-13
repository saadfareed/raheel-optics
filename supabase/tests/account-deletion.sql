\set ON_ERROR_STOP on
-- Hard deletion of an account removes all of its customers, orders, payments, events and receipts, and nothing else.
begin;
do $$
declare target uuid:='11111111-1111-1111-1111-111111111111'; other uuid:='22222222-2222-2222-2222-222222222222'; item record; remaining bigint;
  owner uuid;
begin
  foreach owner in array array[target,other] loop
    perform set_config('request.jwt.claim.sub',owner::text,true);
    perform public.upsert_optical_customer(jsonb_build_object('id',gen_random_uuid(),'action','customer_upsert','baseRevision',0,'payload',
      jsonb_build_object('type','customer_upsert','customer',jsonb_build_object('id',md5(owner::text||'c')::uuid,'name','Customer','mobile','0300 1234567',
      'normalizedMobile',public.normalize_pk_mobile('0300 1234567'),'notes',''))));
    perform public.apply_optical_order(jsonb_build_object('id',gen_random_uuid(),'action','order_create','baseRevision',0,'occurredAt',1000,
      'order',jsonb_build_object('id',md5(owner::text||'o')::uuid,'customerId',md5(owner::text||'c')::uuid,'orderNumber','RO-1','status','ACTIVE',
      'amounts',jsonb_build_object('framePaisa',100000),'payments',jsonb_build_array(jsonb_build_object('id',md5(owner::text||'p')::uuid,'amountPaisa',5000,'receivedAt',1000)))));
  end loop;
  delete from auth.users where id=target;
  for item in select c.table_schema,c.table_name from information_schema.columns c
      where c.table_schema in ('public','legacy') and c.column_name='user_id' loop
    execute format('select count(*) from %I.%I where user_id=$1',item.table_schema,item.table_name) into remaining using target;
    if remaining<>0 then raise exception 'Account data survives in %.%',item.table_schema,item.table_name; end if;
  end loop;
  if not exists(select 1 from public.optical_orders where user_id=other) or not exists(select 1 from public.optical_payments where user_id=other)
    then raise exception 'Another account data removed'; end if;
end $$;
-- The retired to-do API is gone and its tables are no longer in the API schema.
do $$ begin
  if to_regprocedure('public.apply_operation(jsonb)') is not null or to_regclass('public.todos') is not null then raise exception 'Todo API still exposed'; end if;
  if to_regclass('legacy.todos') is null then raise exception 'Todo data was not preserved'; end if;
end $$;
rollback;

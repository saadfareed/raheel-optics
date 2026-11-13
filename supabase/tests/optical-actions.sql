\set ON_ERROR_STOP on
-- Per-action RPCs: status and payment add/refund. Rolls back.
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',false);
select public.upsert_optical_customer(jsonb_build_object('id',gen_random_uuid(),'action','customer_upsert','baseRevision',0,
 'payload',jsonb_build_object('type','customer_upsert','customer',jsonb_build_object('id','c0000000-0000-0000-0000-000000000001',
 'name','Ayesha Khan','mobile','0300 1234567','normalizedMobile',public.normalize_pk_mobile('0300 1234567'),'notes',''))));

do $$
declare a uuid:='a0000000-0000-0000-0000-000000000001'; r jsonb; p jsonb;
begin
  r:=public.apply_optical_order(jsonb_build_object('id','d0000000-0000-0000-0000-000000000001','action','order_create','baseRevision',0,
    'occurredAt',5000,'order',jsonb_build_object('id',a,'customerId','c0000000-0000-0000-0000-000000000001','orderNumber','RO-1',
    'status','ACTIVE','orderedAt',1000,'amounts',jsonb_build_object('framePaisa',100000))));
  if not (r->>'accepted')::boolean then raise exception 'setup %',r; end if;

  -- payment add + replay
  p:=jsonb_build_object('id','d0000000-0000-0000-0000-000000000010','action','payment_add','occurredAt',6000,'orderId',a,
    'payment',jsonb_build_object('id','e0000000-0000-0000-0000-000000000001','amountPaisa',40000,'method','cash','receivedAt',6000,'note','adv'));
  r:=public.optical_payment_add(p);
  if not (r->>'accepted')::boolean or (r->'order'->>'paidPaisa')::bigint<>40000 or (r->'order'->>'revision')::bigint<>2 then raise exception 'Pay %',r; end if;
  if public.optical_payment_add(p)<>r then raise exception 'Payment replay not idempotent'; end if;
  begin perform public.optical_payment_add(p||jsonb_build_object('occurredAt',1)); raise exception 'Reused id accepted';
  exception when raise_exception then if sqlerrm='Reused id accepted' then raise; end if; end;
  if (select count(*) from public.optical_payments where order_id=a)<>1 then raise exception 'Replay duplicated payment'; end if;

  -- second payment, then over-payment raises
  r:=public.optical_payment_add(jsonb_build_object('id','d0000000-0000-0000-0000-000000000011','action','payment_add','occurredAt',6100,'orderId',a,
    'payment',jsonb_build_object('id','e0000000-0000-0000-0000-000000000002','amountPaisa',10000,'method','jazzcash','receivedAt',6100)));
  if (r->'order'->>'paidPaisa')::bigint<>50000 or jsonb_array_length(r->'order'->'payments')<>2 then raise exception 'Second pay %',r; end if;
  begin perform public.optical_payment_add(jsonb_build_object('id',gen_random_uuid(),'action','payment_add','occurredAt',6200,'orderId',a,
      'payment',jsonb_build_object('id',gen_random_uuid(),'amountPaisa',60000,'receivedAt',6200)));
    raise exception 'Overpay accepted';
  exception when raise_exception then if sqlerrm='Overpay accepted' then raise; end if; end;

  -- refund signs the amount; over-refund raises; zero/negative input rejected
  r:=public.optical_payment_add(jsonb_build_object('id',gen_random_uuid(),'action','payment_refund','occurredAt',6300,'orderId',a,
    'payment',jsonb_build_object('id','e0000000-0000-0000-0000-000000000003','amountPaisa',5000,'method','cash','receivedAt',6300)));
  if (r->'order'->>'paidPaisa')::bigint<>45000 then raise exception 'Refund %',r; end if;
  if (select count(*) from public.optical_order_events where order_id=a and event_type='payment_refunded')<>1 then raise exception 'Refund event'; end if;
  begin perform public.optical_payment_add(jsonb_build_object('id',gen_random_uuid(),'action','payment_refund','occurredAt',6400,'orderId',a,
      'payment',jsonb_build_object('id',gen_random_uuid(),'amountPaisa',99000,'receivedAt',6400)));
    raise exception 'Over-refund accepted';
  exception when raise_exception then if sqlerrm='Over-refund accepted' then raise; end if; end;
  begin perform public.optical_payment_add(jsonb_build_object('id',gen_random_uuid(),'action','payment_add','occurredAt',6400,'orderId',a,
      'payment',jsonb_build_object('id',gen_random_uuid(),'amountPaisa',-5,'receivedAt',6400)));
    raise exception 'Negative accepted';
  exception when raise_exception then if sqlerrm='Negative accepted' then raise; end if; end;

  -- status machine
  r:=public.optical_order_status(jsonb_build_object('id',gen_random_uuid(),'action','order_status','occurredAt',7000,'orderId',a,'status','READY_FOR_PICKUP'));
  if r->'order'->>'status'<>'READY_FOR_PICKUP' then raise exception 'Ready %',r; end if;
  r:=public.optical_order_status(jsonb_build_object('id',gen_random_uuid(),'action','order_status','occurredAt',7100,'orderId',a,'status','ACTIVE'));
  if r->'order'->>'status'<>'ACTIVE' then raise exception 'Undo %',r; end if;
  r:=public.optical_order_status(jsonb_build_object('id',gen_random_uuid(),'action','order_status','occurredAt',7200,'orderId',a,'status','COMPLETE'));
  if r->'order'->>'status'<>'COMPLETE' or (r->'order'->>'deliveredAt')::bigint<>7200 then raise exception 'Complete %',r; end if;
  r:=public.optical_order_status(jsonb_build_object('id',gen_random_uuid(),'action','order_status','occurredAt',7300,'orderId',a,'status','ACTIVE'));
  if (r->>'accepted')::boolean then raise exception 'Reopen of completed accepted'; end if;
  -- completed orders still take payments, and payments were preserved
  r:=public.optical_payment_add(jsonb_build_object('id',gen_random_uuid(),'action','payment_add','occurredAt',7400,'orderId',a,
    'payment',jsonb_build_object('id',gen_random_uuid(),'amountPaisa',55000,'receivedAt',7400)));
  if not (r->>'accepted')::boolean or (r->'order'->>'paidPaisa')::bigint<>100000 then raise exception 'Pay after complete %',r; end if;
  begin perform public.optical_order_status(jsonb_build_object('id',gen_random_uuid(),'action','order_status','occurredAt',1,'orderId',a,'status','BOGUS'));
    raise exception 'Bad status accepted';
  exception when raise_exception then if sqlerrm='Bad status accepted' then raise; end if; end;

  -- unknown order is a soft rejection
  r:=public.optical_order_status(jsonb_build_object('id',gen_random_uuid(),'action','order_status','occurredAt',1,'orderId',gen_random_uuid(),'status','COMPLETE'));
  if (r->>'accepted')::boolean then raise exception 'Unknown order accepted'; end if;
  if public.optical_orders_api_version()<2 then raise exception 'api version'; end if;
end $$;

-- Another owner cannot act on this order, and cannot see that it exists.
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false);
do $$ declare r jsonb; begin
  r:=public.optical_order_status(jsonb_build_object('id',gen_random_uuid(),'action','order_status','occurredAt',1,
    'orderId','a0000000-0000-0000-0000-000000000001','status','CANCEL'));
  if (r->>'accepted')::boolean or r->>'message'<>'Order not found on the server.' then raise exception 'Cross-owner status %',r; end if;
  r:=public.optical_payment_add(jsonb_build_object('id',gen_random_uuid(),'action','payment_add','occurredAt',1,
    'orderId','a0000000-0000-0000-0000-000000000001','payment',jsonb_build_object('id',gen_random_uuid(),'amountPaisa',1,'receivedAt',1)));
  if (r->>'accepted')::boolean then raise exception 'Cross-owner payment'; end if;
end $$;
reset role;
rollback;

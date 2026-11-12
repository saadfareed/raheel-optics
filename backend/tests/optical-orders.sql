\set ON_ERROR_STOP on
-- Optical order RPC, ledger, search/filter/sort, analytics and isolation. Rolls back.
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',false);

create function pg_temp.cust(cid uuid, nm text, phone text) returns jsonb language sql as $$
 select public.upsert_optical_customer(jsonb_build_object('id',gen_random_uuid(),'action','customer_upsert','baseRevision',0,
 'payload',jsonb_build_object('type','customer_upsert','customer',jsonb_build_object('id',cid,'name',nm,'mobile',phone,
 'normalizedMobile',public.normalize_pk_mobile(phone),'notes','')))) $$;
create function pg_temp.ord(opid uuid, act text, base bigint, o jsonb) returns jsonb language sql as $$
 select jsonb_build_object('id',opid,'action',act,'baseRevision',base,'occurredAt',5000,'order',o) $$;

select pg_temp.cust('c0000000-0000-0000-0000-000000000001','Ayesha Khan','0300 1234567');
select pg_temp.cust('c0000000-0000-0000-0000-000000000002','Bilal Ahmed','03211234567');

do $$
declare a uuid:='a0000000-0000-0000-0000-000000000001'; c1 uuid:='c0000000-0000-0000-0000-000000000001';
  base jsonb; p jsonb; r jsonb; ok boolean;
begin
  base:=jsonb_build_object('id',a,'customerId',c1,'orderNumber','RO-A','status','ACTIVE','orderedAt',1000,'promisedAt',9000,
    'amounts',jsonb_build_object('framePaisa',50000,'lensPaisa',150000,'discountPaisa',10000),
    'prescription',jsonb_build_object('distanceRight',jsonb_build_object('sphere','-2.00','cylinder','-0.50','axis','90'),
      'distanceLeft',jsonb_build_object('sphere','Plano','prism','1.5')));
  -- create + replay + id reuse with different payload
  p:=pg_temp.ord('d0000000-0000-0000-0000-000000000001','order_create',0,base); r:=public.apply_optical_order(p);
  if not (r->>'accepted')::boolean or r->'order'->>'status'<>'ACTIVE' or (r->'order'->>'totalPaisa')::bigint<>190000
     or (r->'order'->>'revision')::bigint<>1 then raise exception 'Create failed %',r; end if;
  if public.apply_optical_order(p)<>r then raise exception 'Replay not idempotent'; end if;
  begin perform public.apply_optical_order(p||jsonb_build_object('occurredAt',1)); raise exception 'Reused op id accepted';
  exception when raise_exception then if sqlerrm='Reused op id accepted' then raise; end if; end;
  -- payment, stale edit, overpay
  p:=pg_temp.ord('d0000000-0000-0000-0000-000000000002','order_edit',1,base||jsonb_build_object('payments',jsonb_build_array(
    jsonb_build_object('id','e0000000-0000-0000-0000-000000000001','amountPaisa',70000,'method','card','receivedAt',2000))));
  r:=public.apply_optical_order(p);
  if not (r->>'accepted')::boolean or (r->'order'->>'paidPaisa')::bigint<>70000 or (r->'order'->>'balancePaisa')::bigint<>120000
     or r->'order'->'payments'->0->>'method'<>'CARD' then raise exception 'Payment failed %',r; end if;
  r:=public.apply_optical_order(pg_temp.ord('d0000000-0000-0000-0000-000000000003','order_edit',1,base));
  if (r->>'accepted')::boolean then raise exception 'Stale revision accepted'; end if;
  begin perform public.apply_optical_order(pg_temp.ord('d0000000-0000-0000-0000-000000000004','order_edit',2,base||jsonb_build_object('payments',jsonb_build_array(
      jsonb_build_object('id','e0000000-0000-0000-0000-000000000001','amountPaisa',70000,'receivedAt',2000),
      jsonb_build_object('id','e0000000-0000-0000-0000-000000000002','amountPaisa',200000,'receivedAt',2100)))));
    raise exception 'Overpay accepted';
  exception when raise_exception then if sqlerrm='Overpay accepted' then raise; end if; end;
  -- refund then refund larger than paid
  base:=base||jsonb_build_object('payments',jsonb_build_array(
      jsonb_build_object('id','e0000000-0000-0000-0000-000000000001','amountPaisa',70000,'receivedAt',2000),
      jsonb_build_object('id','e0000000-0000-0000-0000-000000000003','amountPaisa',-20000,'receivedAt',2200)));
  r:=public.apply_optical_order(pg_temp.ord('d0000000-0000-0000-0000-000000000005','order_edit',2,base));
  if not (r->>'accepted')::boolean or (r->'order'->>'paidPaisa')::bigint<>50000 then raise exception 'Refund failed %',r; end if;
  if (select count(*) from public.optical_order_events where order_id=a and event_type='payment_refunded')<>1 then raise exception 'Refund event missing'; end if;
  begin perform public.apply_optical_order(pg_temp.ord('d0000000-0000-0000-0000-000000000006','order_edit',3,base||jsonb_build_object('payments',
      (base->'payments')||jsonb_build_array(jsonb_build_object('id','e0000000-0000-0000-0000-000000000004','amountPaisa',-60000,'receivedAt',2300)))));
    raise exception 'Over-refund accepted';
  exception when raise_exception then if sqlerrm='Over-refund accepted' then raise; end if; end;
  -- ledger tamper and removal are rejected
  r:=public.apply_optical_order(pg_temp.ord('d0000000-0000-0000-0000-000000000007','order_edit',3,base||jsonb_build_object('payments',jsonb_build_array(
      jsonb_build_object('id','e0000000-0000-0000-0000-000000000001','amountPaisa',60000,'receivedAt',2000),base->'payments'->1))));
  if (r->>'accepted')::boolean then raise exception 'Ledger tamper accepted'; end if;
  r:=public.apply_optical_order(pg_temp.ord('d0000000-0000-0000-0000-000000000008','order_edit',3,base||jsonb_build_object('payments',jsonb_build_array(base->'payments'->0))));
  if (r->>'accepted')::boolean then raise exception 'Ledger removal accepted'; end if;
  -- prescription validation
  begin perform public.apply_optical_order(pg_temp.ord('d0000000-0000-0000-0000-000000000009','order_edit',3,base||jsonb_build_object('prescription',
      jsonb_build_object('distanceRight',jsonb_build_object('axis','200')))));
    raise exception 'Axis 200 accepted';
  exception when raise_exception then if sqlerrm='Axis 200 accepted' then raise; end if; end;
  begin perform public.apply_optical_order(pg_temp.ord('d0000000-0000-0000-0000-00000000000a','order_edit',3,base||jsonb_build_object('prescription',
      jsonb_build_object('distanceLeft',jsonb_build_object('prism','-1')))));
    raise exception 'Negative prism accepted';
  exception when raise_exception then if sqlerrm='Negative prism accepted' then raise; end if; end;
  begin perform public.apply_optical_order(pg_temp.ord('d0000000-0000-0000-0000-00000000000b','order_edit',3,base||jsonb_build_object('prescription',
      jsonb_build_object('distanceLeft',jsonb_build_object('sphere','abc')))));
    raise exception 'Bad sphere accepted';
  exception when raise_exception then if sqlerrm='Bad sphere accepted' then raise; end if; end;
  -- unknown customer; direct writes denied
  begin perform public.apply_optical_order(pg_temp.ord('d0000000-0000-0000-0000-00000000000c','order_create',0,
      jsonb_build_object('id','a0000000-0000-0000-0000-0000000000ff','customerId','c0000000-0000-0000-0000-0000000000ff','orderNumber','X')));
    raise exception 'Unknown customer accepted';
  exception when raise_exception then if sqlerrm='Unknown customer accepted' then raise; end if; end;
  begin insert into public.optical_orders(id,user_id,customer_id,order_number,ordered_at,updated_at)
      values(gen_random_uuid(),'11111111-1111-1111-1111-111111111111',c1,'DIRECT',1,1);
    raise exception 'Direct insert allowed';
  exception when insufficient_privilege then null; end;
  -- status machine: active -> ready -> active -> complete(+late payment) ; terminal lock
  base:=base||jsonb_build_object('status','READY_FOR_PICKUP');
  r:=public.apply_optical_order(pg_temp.ord('d0000000-0000-0000-0000-000000000010','order_edit',3,base));
  if not (r->>'accepted')::boolean or r->'order'->>'status'<>'READY_FOR_PICKUP' then raise exception 'Ready failed %',r; end if;
  base:=base||jsonb_build_object('status','ACTIVE');
  r:=public.apply_optical_order(pg_temp.ord('d0000000-0000-0000-0000-000000000011','order_edit',4,base));
  if not (r->>'accepted')::boolean then raise exception 'Undo ready failed %',r; end if;
  base:=base||jsonb_build_object('status','COMPLETE');
  r:=public.apply_optical_order(pg_temp.ord('d0000000-0000-0000-0000-000000000012','order_edit',5,base));
  if not (r->>'accepted')::boolean or (r->'order'->>'deliveredAt') is null then raise exception 'Complete failed %',r; end if;
  if (select count(*) from public.optical_order_events where order_id=a and event_type='status_changed')<>3 then raise exception 'Status events wrong'; end if;
  r:=public.apply_optical_order(pg_temp.ord('d0000000-0000-0000-0000-000000000013','order_edit',6,base||jsonb_build_object('status','ACTIVE')));
  if (r->>'accepted')::boolean then raise exception 'Terminal reopened'; end if;
  r:=public.apply_optical_order(pg_temp.ord('d0000000-0000-0000-0000-000000000014','order_edit',6,base||jsonb_build_object('notes','changed after completion')));
  if (r->>'accepted')::boolean then raise exception 'Completed order edited'; end if;
  base:=base||jsonb_build_object('payments',(base->'payments')||jsonb_build_array(
      jsonb_build_object('id','e0000000-0000-0000-0000-000000000005','amountPaisa',140000,'receivedAt',3000)));
  r:=public.apply_optical_order(pg_temp.ord('d0000000-0000-0000-0000-000000000015','order_edit',6,base));
  if not (r->>'accepted')::boolean or (r->'order'->>'balancePaisa')::bigint<>0 or r->'order'->>'status'<>'COMPLETE' then raise exception 'Late payment failed %',r; end if;
  -- order-number collision
  r:=public.apply_optical_order(pg_temp.ord('d0000000-0000-0000-0000-000000000016','order_create',0,jsonb_build_object('id','a0000000-0000-0000-0000-000000000009',
      'customerId',c1,'orderNumber','RO-A','orderedAt',1500)));
  if (r->>'accepted')::boolean then raise exception 'Duplicate order number accepted'; end if;
end $$;

-- Cancelled order for Ayesha (created cancelled), active overdue unpaid order for Bilal.
select public.apply_optical_order(pg_temp.ord('d0000000-0000-0000-0000-000000000020','order_create',0,jsonb_build_object('id','a0000000-0000-0000-0000-000000000002',
  'customerId','c0000000-0000-0000-0000-000000000001','orderNumber','RO-B','status','CANCEL','orderedAt',1200,'amounts',jsonb_build_object('framePaisa',50000))));
select public.apply_optical_order(pg_temp.ord('d0000000-0000-0000-0000-000000000021','order_create',0,jsonb_build_object('id','a0000000-0000-0000-0000-000000000003',
  'customerId','c0000000-0000-0000-0000-000000000002','orderNumber','RO-C','status','ACTIVE','orderedAt',1300,'promisedAt',2000,'amounts',jsonb_build_object('lensPaisa',100000))));

do $$
declare r jsonb;
begin
  -- search before pagination
  if (public.optical_orders_page('bilal')->>'total')::int<>1 then raise exception 'Name search'; end if;
  if (public.optical_orders_page('+92 300 1234')->>'total')::int<>2 then raise exception 'Formatted mobile search'; end if;
  if (public.optical_orders_page('0300-1234567')->>'total')::int<>2 then raise exception 'Local mobile search'; end if;
  if (public.optical_orders_page('1234567')->>'total')::int<>3 then raise exception 'Partial mobile search'; end if;
  if (public.optical_orders_page('RO-C')->>'total')::int<>1 then raise exception 'Order number search'; end if;
  if (public.optical_orders_page('50%')->>'total')::int<>0 then raise exception 'LIKE wildcard not escaped'; end if;
  -- filters
  if (public.optical_orders_page(statuses=>array['ACTIVE'])->>'total')::int<>1 then raise exception 'Status filter'; end if;
  if (public.optical_orders_page(overdue_only=>true)->>'total')::int<>1 then raise exception 'Overdue filter'; end if;
  if (public.optical_orders_page(payment_state=>'unpaid')->>'total')::int<>2 then raise exception 'Unpaid filter'; end if;
  if (public.optical_orders_page(payment_state=>'paid')->>'total')::int<>1 then raise exception 'Paid filter'; end if;
  if (public.optical_orders_page(ordered_from=>1250,ordered_to=>1400)->>'total')::int<>1 then raise exception 'Order date filter'; end if;
  -- sort + paging
  r:=public.optical_orders_page(sort_key=>'name_asc');
  if r->'rows'->0->'customer'->>'name'<>'Ayesha Khan' then raise exception 'Name sort'; end if;
  r:=public.optical_orders_page(sort_key=>'balance_desc');
  if r->'rows'->0->>'orderNumber'<>'RO-C' then raise exception 'Balance sort %',r->'rows'->0; end if;
  r:=public.optical_orders_page(sort_key=>'created_asc',page_size=>1,page_offset=>1);
  if (r->>'total')::int<>3 or jsonb_array_length(r->'rows')<>1 or r->'rows'->0->>'orderNumber'<>'RO-B' then raise exception 'Paging %',r; end if;
  begin perform public.optical_orders_page(sort_key=>'bogus'); raise exception 'Bad sort accepted';
  exception when raise_exception then if sqlerrm='Bad sort accepted' then raise; end if; end;
  -- detail
  r:=public.optical_order_detail('a0000000-0000-0000-0000-000000000001');
  if jsonb_array_length(r->'payments')<>3 or jsonb_array_length(r->'events')<5 then raise exception 'Detail %',r; end if;
  -- analytics (RO-A 190000 + RO-C 100000; cancelled RO-B excluded)
  r:=public.optical_analytics(0,10000000000000);
  if (r->>'salesPaisa')::bigint<>290000 or (r->>'ordersCount')::int<>2 then raise exception 'Sales %',r; end if;
  if (r->>'collectedPaisa')::bigint<>210000 or (r->>'refundedPaisa')::bigint<>20000 or (r->>'netCollectedPaisa')::bigint<>190000 then raise exception 'Cash %',r; end if;
  if (r->>'outstandingPaisa')::bigint<>100000 then raise exception 'Outstanding %',r; end if;
  if (r->'statusCounts'->>'active')::int<>1 or (r->'statusCounts'->>'complete')::int<>1 or (r->'statusCounts'->>'cancelled')::int<>1
     or (r->'statusCounts'->>'readyForPickup')::int<>0 then raise exception 'Status counts %',r; end if;
  if (r->>'averageOrderPaisa')::bigint<>145000 or (r->>'newCustomers')::int<>2 then raise exception 'Average/new %',r; end if;
  r:=public.optical_analytics(1250,1400);
  if (r->>'salesPaisa')::bigint<>100000 or (r->>'collectedPaisa')::bigint<>0 then raise exception 'Period analytics %',r; end if;
  begin perform public.optical_analytics(10,5); raise exception 'Bad period accepted';
  exception when raise_exception then if sqlerrm='Bad period accepted' then raise; end if; end;
end $$;

-- Second owner sees nothing and cannot touch the first owner's order.
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false);
do $$ declare r jsonb; begin
  if (public.optical_orders_page()->>'total')::int<>0 then raise exception 'Cross-owner page leak'; end if;
  if (public.optical_analytics(0,10000000000000)->>'salesPaisa')::bigint<>0 then raise exception 'Cross-owner analytics leak'; end if;
  if public.optical_order_detail('a0000000-0000-0000-0000-000000000001') is not null then raise exception 'Cross-owner detail leak'; end if;
  perform public.upsert_optical_customer(jsonb_build_object('id',gen_random_uuid(),'action','customer_upsert','baseRevision',0,'payload',jsonb_build_object(
    'type','customer_upsert','customer',jsonb_build_object('id','c0000000-0000-0000-0000-0000000000a2','name','Other Shop','mobile','03451234567',
    'normalizedMobile','+923451234567','notes',''))));
  begin
    perform public.apply_optical_order(pg_temp.ord(gen_random_uuid(),'order_edit',1,jsonb_build_object('id','a0000000-0000-0000-0000-000000000001',
      'customerId','c0000000-0000-0000-0000-0000000000a2','orderNumber','HIJACK')));
    raise exception 'Cross-owner edit accepted';
  exception when insufficient_privilege then null; end;
end $$;
reset role;
rollback;

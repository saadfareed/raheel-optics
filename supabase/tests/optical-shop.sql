\set ON_ERROR_STOP on
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',false);

do $$ begin
  if public.normalize_pk_mobile('0300 1234567') <> '+923001234567'
     or public.normalize_pk_mobile('+92-300-1234567') <> '+923001234567'
     or public.normalize_pk_mobile('02134567890') is not null then
    raise exception 'Pakistani mobile normalization failed';
  end if;
end $$;

select public.upsert_optical_customer(jsonb_build_object(
 'id','aaaaaaaa-1111-1111-1111-111111111111','action','customer_upsert','baseRevision',0,
 'payload',jsonb_build_object('type','customer_upsert','customer',jsonb_build_object(
 'id','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','name','Ayesha Khan',
 'mobile','03021234567','normalizedMobile','+923021234567','notes',''))));

do $$ declare r jsonb; begin
  r:=public.apply_optical_order(jsonb_build_object('id','dddddddd-0000-0000-0000-000000000001','action','order_create','baseRevision',0,'occurredAt',1000,
    'order',jsonb_build_object('id','bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb','customerId','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','orderNumber','RO-0001',
      'status','READY_FOR_PICKUP','orderedAt',1000,
      'amounts',jsonb_build_object('framePaisa',50000,'lensPaisa',150000,'discountPaisa',10000),
      'payments',jsonb_build_array(jsonb_build_object('id','cccccccc-cccc-cccc-cccc-cccccccccccc','amountPaisa',70000,'method','cash','receivedAt',1100)))));
  if not (r->>'accepted')::boolean then raise exception 'Order create rejected: %',r; end if;
end $$;

do $$ declare total bigint; collected bigint; begin
  select total_paisa into total from public.optical_orders where order_number='RO-0001';
  select sum(amount_paisa) into collected from public.optical_payments where order_id='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
  if total<>190000 or collected<>70000 or total-collected<>120000 then raise exception 'Optical financial totals failed'; end if;
end $$;

select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false);
do $$ begin
  if exists(select 1 from public.optical_customers) or exists(select 1 from public.optical_orders) then
    raise exception 'Optical RLS owner isolation failed';
  end if;
end $$;

reset role;
rollback;

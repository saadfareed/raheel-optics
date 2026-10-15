set role authenticated;
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',false);

do $$ begin
  if public.normalize_pk_mobile('0300 1234567') <> '+923001234567'
     or public.normalize_pk_mobile('+92-300-1234567') <> '+923001234567'
     or public.normalize_pk_mobile('02134567890') is not null then
    raise exception 'Pakistani mobile normalization failed';
  end if;
end $$;

insert into public.optical_customers(id,user_id,name,mobile,normalized_mobile,created_at,updated_at)
values('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','11111111-1111-1111-1111-111111111111','Ayesha Khan','03001234567','+923001234567',1000,1000);

insert into public.optical_orders(id,user_id,customer_id,order_number,status,frame_paisa,lens_paisa,discount_paisa,ordered_at,updated_at)
values('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb','11111111-1111-1111-1111-111111111111','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','RO-0001','ready_for_pickup',50000,150000,10000,1000,1000);

insert into public.optical_payments(id,user_id,order_id,amount_paisa,received_at,created_at)
values('cccccccc-cccc-cccc-cccc-cccccccccccc','11111111-1111-1111-1111-111111111111','bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',70000,1100,1100);

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

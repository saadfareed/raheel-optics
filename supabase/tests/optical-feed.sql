\set ON_ERROR_STOP on
-- Order feed (cursor, all statuses, totals, isolation) and the todos -> optical one-time copy. Rolls back.
begin;
insert into auth.users(id) values('33333333-3333-3333-3333-333333333333') on conflict do nothing;

-- Copy: a todo-stored order with payments and a refund, a completed one, a non-order todo, a bad mobile.
insert into legacy.todos(id,user_id,status,revision,body) values
 ('b0000000-0000-0000-0000-000000000001','33333333-3333-3333-3333-333333333333','active',3,
  jsonb_build_object('id','b0000000-0000-0000-0000-000000000001','title','Saad','customerMobile','0333 4909162','normalizedMobile','+923334909162',
   'orderNumber','RO-DEMO-0001','orderStatus','READY_FOR_PICKUP','orderedAt',1000,'updatedAt',2000,'dueAt',9000,'notes','n',
   'prescription',jsonb_build_object('distanceRight',jsonb_build_object('sphere','-2.25','cylinder','-0.75','axis',90)),
   'frame',jsonb_build_object('model','Ray-Ban','localImageUri','file:///x.jpg'),'lens',jsonb_build_object('material','Blue cut'),
   'amounts',jsonb_build_object('framePaisa',450000,'lensPaisa',300000),
   'payments',jsonb_build_array(jsonb_build_object('id','e1000000-0000-0000-0000-000000000001','amountPaisa',200000,'method','JAZZCASH','receivedAt',1500),
     jsonb_build_object('id','e1000000-0000-0000-0000-000000000002','amountPaisa',-50000,'method','CASH','receivedAt',1600)))),
 ('b0000000-0000-0000-0000-000000000002','33333333-3333-3333-3333-333333333333','completed',2,
  jsonb_build_object('id','b0000000-0000-0000-0000-000000000002','title','Saad','customerMobile','03334909162','orderNumber','RO-DEMO-0004',
   'orderedAt',1100,'completedAt',3000,'amounts',jsonb_build_object('framePaisa',100000))),
 ('b0000000-0000-0000-0000-000000000003','33333333-3333-3333-3333-333333333333','active',1,jsonb_build_object('id','b0000000-0000-0000-0000-000000000003','title','Buy milk')),
 ('b0000000-0000-0000-0000-000000000004','33333333-3333-3333-3333-333333333333','active',1,
  jsonb_build_object('title','Bad','customerMobile','12','orderNumber','RO-X'));

do $$ declare n integer; begin
  n:=public.optical_backfill_from_todos('33333333-3333-3333-3333-333333333333');
  if n<>2 then raise exception 'Copied % orders, expected 2',n; end if;
  if public.optical_backfill_from_todos('33333333-3333-3333-3333-333333333333')<>0 then raise exception 'Copy is not idempotent'; end if;
  if (select count(*) from public.optical_customers where user_id='33333333-3333-3333-3333-333333333333')<>1 then raise exception 'Customer not shared by mobile'; end if;
  if (select status::text from public.optical_orders where id='b0000000-0000-0000-0000-000000000001')<>'ready_for_pickup'
     or (select status::text from public.optical_orders where id='b0000000-0000-0000-0000-000000000002')<>'complete'
     or (select delivered_at from public.optical_orders where id='b0000000-0000-0000-0000-000000000002')<>3000 then raise exception 'Status mapping'; end if;
  if (select frame ? 'localImageUri' from public.optical_orders where id='b0000000-0000-0000-0000-000000000001') then raise exception 'Local photo path copied'; end if;
  if (select count(*) from public.optical_payments where order_id='b0000000-0000-0000-0000-000000000001' and kind='refund' and amount_paisa=50000)<>1 then raise exception 'Refund not copied'; end if;
end $$;

-- Feed as the owner.
set local role authenticated;
select set_config('request.jwt.claim.sub','33333333-3333-3333-3333-333333333333',false);
do $$ declare r jsonb; first jsonb; cur_u bigint; cur_id uuid; n integer:=0; seen integer:=0; rr jsonb; begin
  r:=public.optical_order_feed(0,'00000000-0000-0000-0000-000000000000',1);
  if jsonb_array_length(r->'rows')<>1 or not (r->>'has_more')::boolean then raise exception 'Paging %',r; end if;
  if (r->'totals'->>'active')::int<>1 or (r->'totals'->>'completed')::int<>1 or (r->'totals'->>'deleted')::int<>0 then raise exception 'Totals %',r->'totals'; end if;
  -- Walk the whole feed one row at a time.
  cur_u:=0; cur_id:='00000000-0000-0000-0000-000000000000';
  loop
    r:=public.optical_order_feed(cur_u,cur_id,1); n:=n+1;
    exit when jsonb_array_length(r->'rows')=0;
    rr:=r->'rows'->0; seen:=seen+1;
    if (rr->>'updatedAt')::bigint<cur_u then raise exception 'Feed went backwards'; end if;
    cur_u:=(rr->>'updatedAt')::bigint; cur_id:=(rr->>'id')::uuid;
    if rr->'customer'->>'name'<>'Saad' then raise exception 'Customer missing %',rr; end if;
    exit when n>10;
  end loop;
  if seen<>2 then raise exception 'Feed saw % orders',seen; end if;
  -- A later change appears after the cursor, with its payments, and only once.
  r:=public.optical_payment_add(jsonb_build_object('id',gen_random_uuid(),'action','payment_add','occurredAt',4000,
    'orderId','b0000000-0000-0000-0000-000000000001','payment',jsonb_build_object('id',gen_random_uuid(),'amountPaisa',10000,'receivedAt',4000)));
  if not (r->>'accepted')::boolean then raise exception 'Payment on copied order %',r; end if;
  r:=public.optical_order_feed(cur_u,cur_id,100);
  if jsonb_array_length(r->'rows')<>1 or r->'rows'->0->>'id'<>'b0000000-0000-0000-0000-000000000001'
     or jsonb_array_length(r->'rows'->0->'payments')<>3 then raise exception 'Change after cursor %',r; end if;
  if (r->'rows'->0->>'updatedAt')::bigint<=cur_u then raise exception 'Stamp not increasing'; end if;
  if (r->'totals'->>'revision')::bigint<>(r->'rows'->0->>'updatedAt')::bigint then raise exception 'Totals revision'; end if;
  if public.optical_orders_api_version()<>3 then raise exception 'api version'; end if;
end $$;

-- Another owner sees nothing.
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',false);
do $$ begin
  if jsonb_array_length(public.optical_order_feed()->'rows')<>0 then raise exception 'Feed leaks across owners'; end if;
end $$;
-- Clients cannot run the copy.
do $$ begin
  begin perform public.optical_backfill_from_todos(); raise exception 'Client ran the copy';
  exception when insufficient_privilege then null; end;
end $$;
reset role;
rollback;

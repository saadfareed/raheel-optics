-- Optical orders become first-class server records. Writes go through apply_optical_order
-- (revision-checked, idempotent, ledger-safe); direct table writes are revoked. Reads use
-- optical_orders_page / optical_order_detail / optical_analytics, which cover the FULL dataset.
-- Not applied to any live project; the todo-compatibility path (019) keeps working until the
-- client is migrated.
begin;

-- Refunds are stored as positive amounts with kind='refund'; signed value = -amount.
alter table public.optical_payments add column kind text not null default 'payment'
  check(kind in ('payment','refund'));
create index optical_orders_owner_ordered on public.optical_orders(user_id,ordered_at desc,id);

create table public.optical_order_receipts (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  payload jsonb not null,
  result jsonb not null
);
alter table public.optical_order_receipts enable row level security;
create policy order_receipts_owned on public.optical_order_receipts for select to authenticated using(user_id=auth.uid());
revoke all on public.optical_order_receipts from public,anon,authenticated;
grant select on public.optical_order_receipts to authenticated;

-- All order writes must use apply_optical_order.
revoke insert,update,delete on public.optical_orders,public.optical_payments,public.optical_order_events from authenticated;

-- Structural prescription validation. Only the known measurement keys are range-checked,
-- at any nesting level; unknown keys are tolerated.
create function public.optical_prescription_error(p jsonb, depth integer default 0) returns text
language plpgsql immutable set search_path=public,pg_temp as $$
declare k text; v jsonb; t text; e text;
begin
  if jsonb_typeof(p) is distinct from 'object' then return 'Prescription must be an object'; end if;
  if depth>4 then return 'Prescription is nested too deeply'; end if;
  for k,v in select key,value from jsonb_each(p) loop
    if jsonb_typeof(v)='object' then
      e:=public.optical_prescription_error(v,depth+1);
      if e is not null then return e; end if;
    elsif jsonb_typeof(v) in ('string','number') and k in ('sphere','cylinder','add','axis','prism') then
      t:=trim(v #>> '{}');
      if t<>'' then
        if k in ('sphere','cylinder','add') then
          if lower(t) not in ('plano','pl') then
            if t !~ '^[+-]?[0-9]{1,2}([.][0-9]{1,2})?$' then return k||' must be a number or Plano'; end if;
            if abs(t::numeric)>30 then return k||' must be between -30 and +30'; end if;
          end if;
        elsif k='axis' then
          if t !~ '^[0-9]{1,3}$' then return 'axis must be a whole number from 1 to 180'; end if;
          if t::integer not between 1 and 180 then return 'axis must be a whole number from 1 to 180'; end if;
        else
          if t !~ '^[0-9]{1,2}([.][0-9]{1,2})?$' then return 'prism must be zero or positive'; end if;
        end if;
      end if;
    end if;
  end loop;
  return null;
end $$;
revoke all on function public.optical_prescription_error(jsonb,integer) from public,anon,authenticated;

create function public.optical_order_json(oid uuid) returns jsonb
language sql stable security invoker set search_path=public,pg_temp as $$
select jsonb_build_object('id',o.id,'customerId',o.customer_id,'orderNumber',o.order_number,
  'status',upper(o.status::text),'prescription',o.prescription,'frame',o.frame,'lens',o.lens,
  'amounts',jsonb_build_object('framePaisa',o.frame_paisa,'lensPaisa',o.lens_paisa,'extrasPaisa',o.extras_paisa,
    'discountPaisa',o.discount_paisa,'taxPaisa',o.tax_paisa),
  'totalPaisa',o.total_paisa,'paidPaisa',coalesce(pp.paid,0),'balancePaisa',greatest(0,o.total_paisa-coalesce(pp.paid,0)),
  'orderedAt',o.ordered_at,'promisedAt',o.promised_at,'reminderAt',o.reminder_at,'deliveredAt',o.delivered_at,
  'notes',o.notes,'revision',o.revision,'updatedAt',o.updated_at,'payments',coalesce(pp.entries,'[]'::jsonb))
from public.optical_orders o
left join lateral (
  select sum(case p.kind when 'refund' then -p.amount_paisa else p.amount_paisa end) paid,
    jsonb_agg(jsonb_build_object('id',p.id,'amountPaisa',case p.kind when 'refund' then -p.amount_paisa else p.amount_paisa end,
      'method',upper(p.method::text),'receivedAt',p.received_at,'note',p.note) order by p.received_at,p.id) entries
  from public.optical_payments p where p.order_id=o.id) pp on true
where o.id=oid
$$;
revoke all on function public.optical_order_json(uuid) from public,anon;
grant execute on function public.optical_order_json(uuid) to authenticated;

create function public.apply_optical_order(payload jsonb) returns jsonb
language plpgsql security definer set search_path=public,pg_temp as $$
declare
  owner uuid:=auth.uid(); op uuid; act text; o jsonb; oid uuid; cid uuid; expected bigint; event_time bigint;
  stamp bigint:=(extract(epoch from clock_timestamp())*1000)::bigint;
  cur public.optical_orders%rowtype; receipt public.optical_order_receipts%rowtype; existing public.optical_payments%rowtype;
  new_status public.optical_order_status; old_status public.optical_order_status;
  amt jsonb; pays jsonb; pay jsonb; match_pay jsonb; field record; e text;
  f bigint; l bigint; x bigint; d bigint; tx bigint; total bigint; pay_total bigint:=0;
  seen uuid[]:='{}'; pid uuid; pamt bigint; existing_signed bigint; pmethod text; order_no text; notes_text text;
  ordered bigint; promised bigint; reminder bigint; delivered bigint; presc jsonb; frame_j jsonb; lens_j jsonb;
  reason text; result jsonb; limit_n integer; n bigint; changed boolean:=false; is_new boolean;
begin
  if owner is null then raise exception 'Sign in required' using errcode='42501'; end if;
  if payload is null or jsonb_typeof(payload)<>'object' then raise exception 'Invalid operation'; end if;
  if octet_length(payload::text)>65536 then raise sqlstate 'PT413' using message='Order too large. Local work is retained.'; end if;
  act:=payload->>'action'; o:=payload->'order';
  if coalesce(payload->>'id','')!~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
     or act is null or act not in ('order_create','order_edit') or jsonb_typeof(o) is distinct from 'object'
     or coalesce(payload->>'baseRevision','')!~'^[0-9]{1,15}$' or coalesce(payload->>'occurredAt','')!~'^[0-9]{1,15}$'
     or coalesce(o->>'id','')!~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    raise exception 'Invalid order operation';
  end if;
  op:=(payload->>'id')::uuid; oid:=(o->>'id')::uuid; expected:=(payload->>'baseRevision')::bigint; event_time:=(payload->>'occurredAt')::bigint;

  perform pg_advisory_xact_lock(hashtextextended(owner::text,81202));
  select * into receipt from public.optical_order_receipts where id=op;
  if found then
    if receipt.user_id<>owner or receipt.payload<>payload then raise exception 'Operation ID already used'; end if;
    return receipt.result;
  end if;

  -- Field validation (invalid data raises; business conflicts below return accepted=false).
  order_no:=trim(coalesce(o->>'orderNumber',''));
  if length(order_no) not between 1 and 40 then raise exception 'Invalid order number'; end if;
  cid:=coalesce(nullif(o->>'customerId',''),nullif(o->'customer'->>'id',''))::uuid;
  if cid is null or not exists(select 1 from public.optical_customers where id=cid and user_id=owner) then raise exception 'Unknown customer'; end if;
  begin new_status:=lower(coalesce(o->>'status','active'))::public.optical_order_status;
  exception when invalid_text_representation then raise exception 'Invalid order status'; end;
  presc:=coalesce(nullif(o->'prescription','null'::jsonb),'{}'::jsonb);
  frame_j:=coalesce(nullif(o->'frame','null'::jsonb),'{}'::jsonb);
  lens_j:=coalesce(nullif(o->'lens','null'::jsonb),'{}'::jsonb);
  if jsonb_typeof(frame_j)<>'object' or jsonb_typeof(lens_j)<>'object' or octet_length(frame_j::text)>8192 or octet_length(lens_j::text)>8192
     or octet_length(presc::text)>8192 then raise exception 'Invalid order details'; end if;
  e:=public.optical_prescription_error(presc);
  if e is not null then raise exception 'Invalid prescription: %',e; end if;
  amt:=coalesce(nullif(o->'amounts','null'::jsonb),'{}'::jsonb);
  if jsonb_typeof(amt)<>'object' then raise exception 'Invalid order amounts'; end if;
  for field in select key,value from jsonb_each(amt) loop
    if field.key not in ('framePaisa','lensPaisa','extrasPaisa','discountPaisa','taxPaisa')
       or jsonb_typeof(field.value)<>'number' or field.value::text !~ '^[0-9]{1,12}$' then raise exception 'Invalid order amount'; end if;
  end loop;
  f:=coalesce((amt->>'framePaisa')::bigint,0); l:=coalesce((amt->>'lensPaisa')::bigint,0); x:=coalesce((amt->>'extrasPaisa')::bigint,0);
  d:=coalesce((amt->>'discountPaisa')::bigint,0); tx:=coalesce((amt->>'taxPaisa')::bigint,0);
  total:=greatest(0,f+l+x-d+tx);
  notes_text:=coalesce(o->>'notes','');
  if length(notes_text)>4000 then raise exception 'Notes are too long'; end if;
  foreach e in array array['orderedAt','promisedAt','reminderAt','deliveredAt'] loop
    if o ? e and o->e<>'null'::jsonb and (jsonb_typeof(o->e)<>'number' or (o->>e)!~'^[0-9]{1,15}$') then raise exception 'Invalid order date'; end if;
  end loop;
  ordered:=coalesce((o->>'orderedAt')::bigint,event_time); promised:=(o->>'promisedAt')::bigint;
  reminder:=(o->>'reminderAt')::bigint; delivered:=(o->>'deliveredAt')::bigint;
  pays:=coalesce(nullif(o->'payments','null'::jsonb),'[]'::jsonb);
  if jsonb_typeof(pays)<>'array' or jsonb_array_length(pays)>200 then raise exception 'Invalid payments'; end if;
  for pay in select value from jsonb_array_elements(pays) loop
    if jsonb_typeof(pay)<>'object' or coalesce(pay->>'id','')!~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       or coalesce(pay->>'amountPaisa','')!~'^-?[1-9][0-9]{0,11}$' or coalesce(pay->>'receivedAt','')!~'^[0-9]{1,15}$'
       or length(coalesce(pay->>'note',''))>1000 then raise exception 'Invalid payment'; end if;
    pmethod:=lower(coalesce(pay->>'method','cash'));
    if pmethod not in ('cash','card','bank_transfer','jazzcash','easypaisa','other') then raise exception 'Invalid payment method'; end if;
    pid:=(pay->>'id')::uuid;
    if pid=any(seen) then raise exception 'Duplicate payment entry'; end if;
    seen:=seen||pid; pay_total:=pay_total+(pay->>'amountPaisa')::bigint;
  end loop;
  if pay_total<0 or pay_total>total then raise exception 'Payment exceeds order total, or refund exceeds paid amount'; end if;

  select * into cur from public.optical_orders where id=oid for update;
  is_new:=not found;
  if not is_new and cur.user_id<>owner then raise exception 'Order not owned' using errcode='42501'; end if;
  old_status:=cur.status;

  if (is_new and (act<>'order_create' or expected<>0)) or (not is_new and (act<>'order_edit' or cur.revision<>expected)) then
    reason:='This order changed on another device. Review its latest details before saving.';
  elsif is_new then
    select coalesce(o2.max_tasks,p.max_tasks) into limit_n from public.resource_policy p
      left join (select null::integer max_tasks) o2 on true where p.id=true;
    select count(*) into n from public.optical_orders where user_id=owner;
    if n>=coalesce(limit_n,1000000) then raise sqlstate 'PT429' using message='Account order limit reached. Contact support; local work is retained.'; end if;
  else
    if old_status='cancel' then reason:='Cancelled orders cannot be edited.';
    elsif old_status='complete' and (new_status<>'complete' or order_no<>cur.order_number or cid<>cur.customer_id
      or f<>cur.frame_paisa or l<>cur.lens_paisa or x<>cur.extras_paisa or d<>cur.discount_paisa or tx<>cur.tax_paisa
      or presc<>cur.prescription or frame_j<>cur.frame or lens_j<>cur.lens or notes_text<>cur.notes
      or ordered<>cur.ordered_at or promised is distinct from cur.promised_at) then
      reason:='Completed orders can only receive payments.';
    elsif new_status<>old_status and not ((old_status='active' and new_status in ('ready_for_pickup','complete','cancel'))
      or (old_status='ready_for_pickup' and new_status in ('active','complete','cancel'))) then
      reason:='This status change is not allowed.';
    end if;
    if reason is null then
      for existing in select * from public.optical_payments where order_id=oid loop
        select value into match_pay from jsonb_array_elements(pays) where (value->>'id')::uuid=existing.id;
        existing_signed:=case existing.kind when 'refund' then -existing.amount_paisa else existing.amount_paisa end;
        if match_pay is null or (match_pay->>'amountPaisa')::bigint<>existing_signed
           or (match_pay->>'receivedAt')::bigint<>existing.received_at then
          reason:='Existing payment entries cannot be changed or removed.'; exit;
        end if;
      end loop;
    end if;
  end if;

  if reason is null then
    if new_status='complete' then delivered:=coalesce(delivered,cur.delivered_at,event_time); else delivered:=null; end if;
    changed:=is_new or order_no<>cur.order_number or cid<>cur.customer_id or f<>cur.frame_paisa or l<>cur.lens_paisa or x<>cur.extras_paisa
      or d<>cur.discount_paisa or tx<>cur.tax_paisa or presc<>cur.prescription or frame_j<>cur.frame or lens_j<>cur.lens
      or notes_text<>cur.notes or ordered<>cur.ordered_at or promised is distinct from cur.promised_at or reminder is distinct from cur.reminder_at;
    begin
      insert into public.optical_orders(id,user_id,customer_id,order_number,status,prescription,frame,lens,frame_paisa,lens_paisa,extras_paisa,
        discount_paisa,tax_paisa,ordered_at,promised_at,reminder_at,delivered_at,notes,revision,updated_at)
      values(oid,owner,cid,order_no,new_status,presc,frame_j,lens_j,f,l,x,d,tx,ordered,promised,reminder,delivered,notes_text,expected+1,stamp)
      on conflict(id) do update set customer_id=excluded.customer_id,order_number=excluded.order_number,status=excluded.status,
        prescription=excluded.prescription,frame=excluded.frame,lens=excluded.lens,frame_paisa=excluded.frame_paisa,lens_paisa=excluded.lens_paisa,
        extras_paisa=excluded.extras_paisa,discount_paisa=excluded.discount_paisa,tax_paisa=excluded.tax_paisa,ordered_at=excluded.ordered_at,
        promised_at=excluded.promised_at,reminder_at=excluded.reminder_at,delivered_at=excluded.delivered_at,notes=excluded.notes,
        revision=excluded.revision,updated_at=excluded.updated_at;
    exception when unique_violation then reason:='This order number is already used.'; end;
  end if;

  if reason is null then
    if is_new then
      insert into public.optical_order_events(id,user_id,order_id,event_type,occurred_at,payload)
        values(gen_random_uuid(),owner,oid,'created',event_time,jsonb_build_object('status',upper(new_status::text)));
    elsif new_status<>old_status then
      insert into public.optical_order_events(id,user_id,order_id,event_type,occurred_at,payload)
        values(gen_random_uuid(),owner,oid,'status_changed',event_time,jsonb_build_object('from',upper(old_status::text),'to',upper(new_status::text)));
    elsif changed then
      insert into public.optical_order_events(id,user_id,order_id,event_type,occurred_at,payload)
        values(gen_random_uuid(),owner,oid,'edited',event_time,'{}'::jsonb);
    end if;
    for pay in select value from jsonb_array_elements(pays) loop
      pid:=(pay->>'id')::uuid;
      if exists(select 1 from public.optical_payments where id=pid) then continue; end if;
      pamt:=(pay->>'amountPaisa')::bigint;
      insert into public.optical_payments(id,user_id,order_id,amount_paisa,method,received_at,note,created_at,kind)
        values(pid,owner,oid,abs(pamt),lower(coalesce(pay->>'method','cash'))::public.optical_payment_method,(pay->>'receivedAt')::bigint,
          coalesce(pay->>'note',''),stamp,case when pamt<0 then 'refund' else 'payment' end);
      insert into public.optical_order_events(id,user_id,order_id,event_type,occurred_at,payload)
        values(gen_random_uuid(),owner,oid,case when pamt<0 then 'payment_refunded' else 'payment_added' end,(pay->>'receivedAt')::bigint,
          jsonb_build_object('paymentId',pid,'amountPaisa',abs(pamt)));
    end loop;
    result:=jsonb_build_object('accepted',true,'order',public.optical_order_json(oid));
  else
    result:=jsonb_build_object('accepted',false,'message',reason,
      'order',case when is_new then null else public.optical_order_json(oid) end);
  end if;
  insert into public.optical_order_receipts(id,user_id,payload,result) values(op,owner,payload,result);
  return result;
end $$;
revoke all on function public.apply_optical_order(jsonb) from public,anon;
grant execute on function public.apply_optical_order(jsonb) to authenticated;

-- Search/filter/sort over ALL of the owner's orders before pagination.
create function public.optical_orders_page(
  search text default null, statuses text[] default null, payment_state text default null,
  ordered_from bigint default null, ordered_to bigint default null,
  promised_from bigint default null, promised_to bigint default null,
  overdue_only boolean default false, sort_key text default 'created_desc',
  page_offset integer default 0, page_size integer default 50) returns jsonb
language plpgsql stable security invoker set search_path=public,pg_temp as $$
declare
  q text:=nullif(trim(coalesce(search,'')),''); pattern text; national text;
  now_ms bigint:=(extract(epoch from clock_timestamp())*1000)::bigint;
  lim integer:=greatest(1,least(coalesce(page_size,50),100)); off integer:=greatest(0,coalesce(page_offset,0));
  result jsonb;
begin
  if auth.uid() is null then raise exception 'Sign in required' using errcode='42501'; end if;
  if coalesce(sort_key,'created_desc') not in ('created_desc','created_asc','updated_desc','promised_asc','name_asc','name_desc','balance_desc') then
    raise exception 'Unknown sort';
  end if;
  if payment_state is not null and payment_state not in ('unpaid','partial','paid') then raise exception 'Unknown payment state'; end if;
  if q is not null then
    pattern:='%'||replace(replace(replace(q,'\','\\'),'%','\%'),'_','\_')||'%';
    if q ~ '^[0-9+()\s-]+$' then
      national:=regexp_replace(regexp_replace(q,'[^0-9]','','g'),'^(0092|92|0)','');
      if length(national)<3 then national:=null; end if;
    end if;
  end if;
  with base as (
    select o.id,o.order_number,o.status,o.ordered_at,o.promised_at,o.reminder_at,o.delivered_at,o.total_paisa,o.revision,o.updated_at,
      c.id customer_id,c.name customer_name,c.mobile customer_mobile,
      coalesce((select sum(case p.kind when 'refund' then -p.amount_paisa else p.amount_paisa end) from public.optical_payments p where p.order_id=o.id),0) paid
    from public.optical_orders o join public.optical_customers c on c.id=o.customer_id
    where o.user_id=auth.uid()
      and (q is null or c.name ilike pattern or o.order_number ilike pattern
           or (national is not null and substring(c.normalized_mobile from 2) like '%'||national||'%'))
      and (statuses is null or o.status::text=any(array(select lower(s) from unnest(statuses) s)))
      and (ordered_from is null or o.ordered_at>=ordered_from) and (ordered_to is null or o.ordered_at<ordered_to)
      and (promised_from is null or o.promised_at>=promised_from) and (promised_to is null or o.promised_at<promised_to)
      and (not coalesce(overdue_only,false) or (o.promised_at<now_ms and o.status in ('active','ready_for_pickup')))
  ), filtered as (
    select *,greatest(0,total_paisa-paid) balance from base
    where payment_state is null
      or (payment_state='unpaid' and paid<=0) or (payment_state='partial' and paid>0 and paid<total_paisa)
      or (payment_state='paid' and paid>=total_paisa)
  ), ranked as (
    select *,row_number() over (order by
      case when sort_key='created_desc' then ordered_at end desc,
      case when sort_key='created_asc' then ordered_at end asc,
      case when sort_key='updated_desc' then updated_at end desc,
      case when sort_key='promised_asc' then promised_at end asc nulls last,
      case when sort_key='name_asc' then lower(customer_name) end asc,
      case when sort_key='name_desc' then lower(customer_name) end desc,
      case when sort_key='balance_desc' then balance end desc,
      id) rn from filtered
  )
  select jsonb_build_object('total',(select count(*) from filtered),'offset',off,
    'rows',coalesce((select jsonb_agg(jsonb_build_object('id',id,'orderNumber',order_number,'status',upper(status::text),
      'customer',jsonb_build_object('id',customer_id,'name',customer_name,'mobile',customer_mobile),
      'orderedAt',ordered_at,'promisedAt',promised_at,'reminderAt',reminder_at,'deliveredAt',delivered_at,
      'totalPaisa',total_paisa,'paidPaisa',paid,'balancePaisa',balance,'revision',revision,'updatedAt',updated_at) order by rn)
      from ranked where rn>off and rn<=off+lim),'[]'::jsonb))
  into result;
  return result;
end $$;
revoke all on function public.optical_orders_page(text,text[],text,bigint,bigint,bigint,bigint,boolean,text,integer,integer) from public,anon;
grant execute on function public.optical_orders_page(text,text[],text,bigint,bigint,bigint,bigint,boolean,text,integer,integer) to authenticated;

create function public.optical_order_detail(order_id uuid) returns jsonb
language sql stable security invoker set search_path=public,pg_temp as $$
select public.optical_order_json(o.id) || jsonb_build_object('events',coalesce((
  select jsonb_agg(jsonb_build_object('id',e.id,'type',e.event_type,'occurredAt',e.occurred_at,'payload',e.payload) order by e.occurred_at desc,e.id)
  from (select * from public.optical_order_events where order_id=o.id order by occurred_at desc,id limit 200) e),'[]'::jsonb))
from public.optical_orders o where o.id=order_id and o.user_id=auth.uid()
$$;
revoke all on function public.optical_order_detail(uuid) from public,anon;
grant execute on function public.optical_order_detail(uuid) to authenticated;

-- Full-dataset reconciliation over [from_ms, to_ms). Sales exclude cancelled orders; collected and
-- refunds come from dated ledger rows; outstanding and status counts are current backlog.
create function public.optical_analytics(from_ms bigint, to_ms bigint) returns jsonb
language plpgsql stable security invoker set search_path=public,pg_temp as $$
declare owner uuid:=auth.uid(); result jsonb;
begin
  if owner is null then raise exception 'Sign in required' using errcode='42501'; end if;
  if from_ms is null or to_ms is null or to_ms<=from_ms then raise exception 'Invalid period'; end if;
  select jsonb_build_object(
    'periodStart',from_ms,'periodEnd',to_ms,
    'salesPaisa',coalesce((select sum(total_paisa) from public.optical_orders where user_id=owner and status<>'cancel' and ordered_at>=from_ms and ordered_at<to_ms),0),
    'ordersCount',(select count(*) from public.optical_orders where user_id=owner and status<>'cancel' and ordered_at>=from_ms and ordered_at<to_ms),
    'collectedPaisa',coalesce((select sum(amount_paisa) from public.optical_payments where user_id=owner and kind='payment' and received_at>=from_ms and received_at<to_ms),0),
    'refundedPaisa',coalesce((select sum(amount_paisa) from public.optical_payments where user_id=owner and kind='refund' and received_at>=from_ms and received_at<to_ms),0),
    'outstandingPaisa',coalesce((select sum(greatest(0,o.total_paisa-coalesce(p.paid,0))) from public.optical_orders o
      left join lateral (select sum(case kind when 'refund' then -amount_paisa else amount_paisa end) paid from public.optical_payments where order_id=o.id) p on true
      where o.user_id=owner and o.status<>'cancel'),0),
    'statusCounts',jsonb_build_object(
      'active',(select count(*) from public.optical_orders where user_id=owner and status='active'),
      'readyForPickup',(select count(*) from public.optical_orders where user_id=owner and status='ready_for_pickup'),
      'complete',(select count(*) from public.optical_orders where user_id=owner and status='complete'),
      'cancelled',(select count(*) from public.optical_orders where user_id=owner and status='cancel')),
    'newCustomers',(select count(*) from public.optical_customers where user_id=owner and created_at>=from_ms and created_at<to_ms),
    'returningCustomers',(select count(distinct o.customer_id) from public.optical_orders o where o.user_id=owner and o.status<>'cancel'
      and o.ordered_at>=from_ms and o.ordered_at<to_ms
      and exists(select 1 from public.optical_orders e where e.customer_id=o.customer_id and e.user_id=owner and e.ordered_at<from_ms))
  ) into result;
  result:=result||jsonb_build_object('averageOrderPaisa',case when (result->>'ordersCount')::bigint=0 then 0
    else ((result->>'salesPaisa')::numeric/(result->>'ordersCount')::numeric)::bigint end,
    'netCollectedPaisa',(result->>'collectedPaisa')::bigint-(result->>'refundedPaisa')::bigint);
  return result;
end $$;
revoke all on function public.optical_analytics(bigint,bigint) from public,anon;
grant execute on function public.optical_analytics(bigint,bigint) to authenticated;

create function public.optical_orders_api_version() returns integer
language sql stable set search_path=public,pg_temp as $$select 1$$;
revoke all on function public.optical_orders_api_version() from public,anon;
grant execute on function public.optical_orders_api_version() to authenticated;
notify pgrst, 'reload schema';
commit;

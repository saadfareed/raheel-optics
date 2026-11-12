-- Order download feed for devices, and a one-time copy of orders that were stored as todos.
--
-- optical_order_feed(after_updated, after_id, page_size) -> {rows[], has_more, totals}
--   Every order of the caller (all statuses, full detail incl. payments and customer) changed after the cursor,
--   ordered by (updatedAt, id). Devices keep the last row's (updatedAt, id) as their cursor.
--   totals: {active (active + ready), completed, deleted (cancelled), revision (latest updatedAt)}.
-- optical_backfill_from_todos(target) copies todo-stored orders into optical_* (service role only; idempotent).
begin;

-- A cursor feed is only safe if a row can never appear behind the cursor. apply_optical_order serialises each
-- owner with an advisory lock; this trigger makes every write's stamp strictly greater than any earlier one.
create function public.optical_orders_stamp() returns trigger
language plpgsql set search_path=public,pg_temp as $$
begin
  new.updated_at:=greatest(new.updated_at,
    coalesce((select max(updated_at) from public.optical_orders where user_id=new.user_id and id<>new.id),0)+1);
  return new;
end $$;
create trigger optical_orders_stamp before insert or update on public.optical_orders
  for each row execute function public.optical_orders_stamp();
create index optical_orders_owner_updated on public.optical_orders(user_id,updated_at,id);

create function public.optical_order_feed(after_updated bigint default 0,
  after_id uuid default '00000000-0000-0000-0000-000000000000', page_size integer default 100) returns jsonb
language plpgsql stable security definer set search_path=public,pg_temp as $$
declare owner uuid:=auth.uid(); n integer:=least(greatest(coalesce(page_size,100),1),200); page jsonb; more boolean;
begin
  if owner is null then raise exception 'Sign in required' using errcode='42501'; end if;
  with candidates as (
    select o.id,o.updated_at from public.optical_orders o
    where o.user_id=owner and (o.updated_at,o.id)>(coalesce(after_updated,0),coalesce(after_id,'00000000-0000-0000-0000-000000000000'::uuid))
    order by o.updated_at,o.id limit n+1)
  select coalesce(jsonb_agg(public.optical_order_json(x.id)||jsonb_build_object('customer',jsonb_build_object(
      'id',c.id,'name',c.name,'mobile',c.mobile,'normalizedMobile',c.normalized_mobile)) order by x.updated_at,x.id),'[]'::jsonb)
    into page
  from (select * from candidates order by updated_at,id limit n) x
  join public.optical_orders o on o.id=x.id join public.optical_customers c on c.id=o.customer_id;
  select count(*)>n into more from (select 1 from public.optical_orders o where o.user_id=owner
    and (o.updated_at,o.id)>(coalesce(after_updated,0),coalesce(after_id,'00000000-0000-0000-0000-000000000000'::uuid)) limit n+1) t;
  return jsonb_build_object('rows',page,'has_more',more,'totals',(select jsonb_build_object(
    'active',count(*) filter(where status in ('active','ready_for_pickup')),'completed',count(*) filter(where status='complete'),
    'deleted',count(*) filter(where status='cancel'),'revision',coalesce(max(updated_at),0))
    from public.optical_orders where user_id=owner));
end $$;
revoke all on function public.optical_order_feed(bigint,uuid,integer) from public,anon;
grant execute on function public.optical_order_feed(bigint,uuid,integer) to authenticated;

-- One-time copy. Orders are todos whose body has an order number and a valid Pakistani mobile. Existing optical
-- rows are never overwritten; customers are matched by owner + normalized mobile. Returns the number of orders copied.
create function public.optical_backfill_from_todos(target uuid default null) returns integer
language plpgsql security definer set search_path=public,pg_temp as $$
declare t record; b jsonb; cid uuid; oid uuid; norm text; nm text; st public.optical_order_status; num text;
  stamp bigint:=(extract(epoch from clock_timestamp())*1000)::bigint; ordered bigint; pay jsonb; pamt bigint; pid uuid;
  copied integer:=0; uuid_re constant text:='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';
  amt jsonb; frame_j jsonb;
  function_amount constant text:='^[0-9]{1,12}$';
begin
  -- todos lives in public until migration 023 moves it to the legacy schema.
  if coalesce(to_regclass('public.todos'),to_regclass('legacy.todos')) is null then return 0; end if;
  for t in execute format('select id,user_id,status,body from %s where ($1 is null or user_id=$1)
      and coalesce(trim(body->>''orderNumber''),'''')<>'''' order by user_id,id',coalesce(to_regclass('public.todos'),to_regclass('legacy.todos'))) using target loop
    b:=t.body; oid:=t.id;
    if exists(select 1 from public.optical_orders where id=oid) then continue; end if;
    norm:=public.normalize_pk_mobile(coalesce(b->>'customerMobile',''));
    if norm is null then continue; end if;
    nm:=left(trim(coalesce(b->>'title','')),120);
    if length(nm)<2 then nm:='Customer'; end if;
    select id into cid from public.optical_customers where user_id=t.user_id and normalized_mobile=norm;
    if cid is null then
      cid:=case when coalesce(b->>'customerId','')~*uuid_re and not exists(select 1 from public.optical_customers where id=(b->>'customerId')::uuid)
        then (b->>'customerId')::uuid else gen_random_uuid() end;
      insert into public.optical_customers(id,user_id,name,mobile,normalized_mobile,notes,created_at,updated_at,revision,latest_prescription)
        values(cid,t.user_id,nm,trim(b->>'customerMobile'),norm,'',coalesce(nullif(b->>'orderedAt','')::bigint,stamp),stamp,1,
          case when jsonb_typeof(b->'prescription')='object' then b->'prescription' end);
    end if;
    st:=case when t.status='completed' then 'complete' when t.status='deleted' then 'cancel'
      when upper(coalesce(b->>'orderStatus','')) in ('READY','READY_FOR_PICKUP') then 'ready_for_pickup'
      when upper(coalesce(b->>'orderStatus','')) in ('DELIVERED','COMPLETE') then 'complete'
      when upper(coalesce(b->>'orderStatus','')) in ('CANCELLED','CANCEL') then 'cancel' else 'active' end;
    num:=left(trim(b->>'orderNumber'),40);
    if exists(select 1 from public.optical_orders where user_id=t.user_id and order_number=num) then num:=left(num,35)||'-'||left(oid::text,4); end if;
    amt:=case when jsonb_typeof(b->'amounts')='object' then b->'amounts' else '{}'::jsonb end;
    frame_j:=case when jsonb_typeof(b->'frame')='object' then (b->'frame')-'localImageUri' else '{}'::jsonb end;
    ordered:=coalesce(case when coalesce(b->>'orderedAt','')~'^[0-9]{1,15}$' then (b->>'orderedAt')::bigint end,stamp);
    insert into public.optical_orders(id,user_id,customer_id,order_number,status,prescription,frame,lens,frame_paisa,lens_paisa,extras_paisa,
        discount_paisa,tax_paisa,ordered_at,promised_at,reminder_at,delivered_at,notes,revision,updated_at)
      values(oid,t.user_id,cid,num,st,
        case when jsonb_typeof(b->'prescription')='object' then b->'prescription' else '{}'::jsonb end,frame_j,
        case when jsonb_typeof(b->'lens')='object' then b->'lens' else '{}'::jsonb end,
        case when coalesce(amt->>'framePaisa','')~function_amount then (amt->>'framePaisa')::bigint else 0 end,
        case when coalesce(amt->>'lensPaisa','')~function_amount then (amt->>'lensPaisa')::bigint else 0 end,
        case when coalesce(amt->>'extrasPaisa','')~function_amount then (amt->>'extrasPaisa')::bigint else 0 end,
        case when coalesce(amt->>'discountPaisa','')~function_amount then (amt->>'discountPaisa')::bigint else 0 end,
        case when coalesce(amt->>'taxPaisa','')~function_amount then (amt->>'taxPaisa')::bigint else 0 end,
        ordered,
        case when coalesce(b->>'dueAt','')~'^[0-9]{1,15}$' then (b->>'dueAt')::bigint end,
        case when st in ('active','ready_for_pickup') and coalesce(b->>'reminderAt','')~'^[0-9]{1,15}$' then (b->>'reminderAt')::bigint end,
        case when st='complete' then coalesce(
          case when coalesce(b->>'deliveredAt','')~'^[0-9]{1,15}$' then (b->>'deliveredAt')::bigint end,
          case when coalesce(b->>'completedAt','')~'^[0-9]{1,15}$' then (b->>'completedAt')::bigint end,stamp) end,
        left(coalesce(b->>'notes',''),4000),1,
        coalesce(case when coalesce(b->>'updatedAt','')~'^[0-9]{1,15}$' then (b->>'updatedAt')::bigint end,stamp));
    insert into public.optical_order_events(id,user_id,order_id,event_type,occurred_at,payload)
      values(gen_random_uuid(),t.user_id,oid,'created',ordered,jsonb_build_object('status',upper(st::text),'source','todos'));
    for pay in select value from jsonb_array_elements(case when jsonb_typeof(b->'payments')='array' then b->'payments' else '[]'::jsonb end) loop
      if coalesce(pay->>'amountPaisa','')!~'^-?[0-9]{1,12}$' then continue; end if;
      pamt:=(pay->>'amountPaisa')::bigint;
      if pamt=0 then continue; end if;
      pid:=case when coalesce(pay->>'id','')~*uuid_re then (pay->>'id')::uuid else gen_random_uuid() end;
      if exists(select 1 from public.optical_payments where id=pid) then continue; end if;
      insert into public.optical_payments(id,user_id,order_id,amount_paisa,method,received_at,note,created_at,kind)
        values(pid,t.user_id,oid,abs(pamt),
          (case when lower(coalesce(pay->>'method','cash')) in ('cash','card','bank_transfer','jazzcash','easypaisa','other')
            then lower(coalesce(pay->>'method','cash')) else 'cash' end)::public.optical_payment_method,
          case when coalesce(pay->>'receivedAt','')~'^[0-9]{1,15}$' then (pay->>'receivedAt')::bigint else ordered end,
          left(coalesce(pay->>'note',''),1000),stamp,case when pamt<0 then 'refund' else 'payment' end);
    end loop;
    copied:=copied+1;
  end loop;
  return copied;
end $$;
revoke all on function public.optical_backfill_from_todos(uuid) from public,anon,authenticated;

select public.optical_backfill_from_todos();

create or replace function public.optical_orders_api_version() returns integer
language sql stable set search_path=public,pg_temp as $$select 3$$;
notify pgrst, 'reload schema';
commit;

-- Raheel Optics domain. This is intentionally separate from the legacy todo tables so
-- existing Daymark installations can be migrated or retired without corrupting data.
begin;

create type public.optical_order_status as enum ('new','in_production','ready','delivered','cancelled');
create type public.optical_payment_method as enum ('cash','card','bank_transfer','jazzcash','easypaisa','other');

create or replace function public.normalize_pk_mobile(value text) returns text
language plpgsql immutable strict set search_path=public,pg_temp as $$
declare digits text := regexp_replace(trim(value),'[^0-9+]','','g'); national text;
begin
  national := case
    when digits like '+92%' then substring(digits from 4)
    when digits like '0092%' then substring(digits from 5)
    when digits like '92%' then substring(digits from 3)
    when digits like '0%' then substring(digits from 2)
    else digits
  end;
  if national !~ '^3[0-9]{9}$' then return null; end if;
  return '+92'||national;
end $$;

create table public.optical_shop_profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  shop_name text not null default 'Raheel Optics' check(length(trim(shop_name)) between 2 and 120),
  address text not null default '',
  phone text not null default '',
  normalized_phone text,
  whatsapp text not null default '',
  currency text not null default 'PKR' check(currency='PKR'),
  timezone text not null default 'Asia/Karachi' check(timezone='Asia/Karachi'),
  order_prefix text not null default 'RO' check(order_prefix ~ '^[A-Z0-9-]{1,10}$'),
  default_turnaround_days integer not null default 3 check(default_turnaround_days between 0 and 90),
  ready_message_template text not null default 'Hello {customer_name}, your glasses for order {order_number} are ready for pickup from {shop_name}. Pickup hours: {pickup_hours}. Amount due: {balance_due}. If the shop is closed or you need assistance, contact us at {shop_phone}. Thank you.',
  revision bigint not null default 1,
  updated_at bigint not null,
  check(normalized_phone is null or normalized_phone=public.normalize_pk_mobile(phone))
);

create table public.optical_shop_hours (
  user_id uuid not null references auth.users(id) on delete cascade,
  day_of_week smallint not null check(day_of_week between 1 and 7),
  interval_index smallint not null default 0 check(interval_index between 0 and 3),
  opens_at time,
  closes_at time,
  closed boolean not null default false,
  primary key(user_id,day_of_week,interval_index),
  check(closed or (opens_at is not null and closes_at is not null and opens_at<closes_at))
);

create table public.optical_customers (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null check(length(trim(name)) between 2 and 120),
  mobile text not null,
  normalized_mobile text not null check(normalized_mobile=public.normalize_pk_mobile(mobile)),
  alternate_mobile text,
  notes text not null default '' check(length(notes)<=4000),
  created_at bigint not null,
  updated_at bigint not null,
  revision bigint not null default 1
);
create index optical_customers_owner_phone on public.optical_customers(user_id,normalized_mobile);
create index optical_customers_owner_name on public.optical_customers(user_id,lower(name),id);

create table public.optical_orders (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  customer_id uuid not null references public.optical_customers(id),
  order_number text not null check(length(trim(order_number)) between 1 and 40),
  status public.optical_order_status not null default 'new',
  prescription jsonb not null default '{}'::jsonb,
  frame jsonb not null default '{}'::jsonb,
  lens jsonb not null default '{}'::jsonb,
  frame_paisa bigint not null default 0 check(frame_paisa>=0),
  lens_paisa bigint not null default 0 check(lens_paisa>=0),
  extras_paisa bigint not null default 0 check(extras_paisa>=0),
  discount_paisa bigint not null default 0 check(discount_paisa>=0),
  tax_paisa bigint not null default 0 check(tax_paisa>=0),
  total_paisa bigint generated always as (greatest(0,frame_paisa+lens_paisa+extras_paisa-discount_paisa+tax_paisa)) stored,
  ordered_at bigint not null,
  promised_at bigint,
  reminder_at bigint,
  delivered_at bigint,
  notes text not null default '' check(length(notes)<=4000),
  revision bigint not null default 1,
  updated_at bigint not null,
  unique(user_id,order_number),
  check(status<>'delivered' or delivered_at is not null)
);
create index optical_orders_owner_status_date on public.optical_orders(user_id,status,ordered_at desc,id);
create index optical_orders_owner_promised on public.optical_orders(user_id,promised_at,id) where promised_at is not null;
create index optical_orders_customer_date on public.optical_orders(customer_id,ordered_at desc,id);

create table public.optical_payments (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  order_id uuid not null references public.optical_orders(id) on delete cascade,
  amount_paisa bigint not null check(amount_paisa>0),
  method public.optical_payment_method not null default 'cash',
  received_at bigint not null,
  note text not null default '' check(length(note)<=1000),
  created_at bigint not null
);
create index optical_payments_owner_date on public.optical_payments(user_id,received_at desc,id);
create index optical_payments_order on public.optical_payments(order_id,received_at,id);

create table public.optical_order_events (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  order_id uuid not null references public.optical_orders(id) on delete cascade,
  event_type text not null check(event_type in ('created','edited','status_changed','payment_added','payment_refunded','image_added','image_removed','message_shared')),
  occurred_at bigint not null,
  payload jsonb not null default '{}'::jsonb
);
create index optical_order_events_order_time on public.optical_order_events(order_id,occurred_at desc,id);

create table public.optical_frame_images (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  order_id uuid not null references public.optical_orders(id) on delete cascade,
  object_path text not null,
  content_type text not null check(content_type in ('image/jpeg','image/png','image/webp')),
  byte_size bigint not null check(byte_size between 1 and 10485760),
  created_at bigint not null,
  unique(user_id,object_path)
);

alter table public.optical_shop_profiles enable row level security;
alter table public.optical_shop_hours enable row level security;
alter table public.optical_customers enable row level security;
alter table public.optical_orders enable row level security;
alter table public.optical_payments enable row level security;
alter table public.optical_order_events enable row level security;
alter table public.optical_frame_images enable row level security;

create policy optical_shop_owned on public.optical_shop_profiles for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
create policy optical_hours_owned on public.optical_shop_hours for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
create policy optical_customers_owned on public.optical_customers for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
create policy optical_orders_owned on public.optical_orders for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
create policy optical_payments_owned on public.optical_payments for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
create policy optical_events_owned on public.optical_order_events for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());
create policy optical_images_owned on public.optical_frame_images for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());

revoke all on public.optical_shop_profiles,public.optical_shop_hours,public.optical_customers,public.optical_orders,
  public.optical_payments,public.optical_order_events,public.optical_frame_images from anon;
grant select,insert,update,delete on public.optical_shop_profiles,public.optical_shop_hours,public.optical_customers,public.optical_orders,
  public.optical_payments,public.optical_order_events,public.optical_frame_images to authenticated;

-- A private bucket. Object paths must begin with the authenticated user id. The
-- guard keeps the plain-Postgres CI harness usable; Supabase always has storage.
do $$ begin
  if to_regclass('storage.buckets') is not null and to_regclass('storage.objects') is not null then
    insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
    values('optical-frames','optical-frames',false,10485760,array['image/jpeg','image/png','image/webp'])
    on conflict(id) do nothing;
    execute $policy$create policy optical_frame_objects_read on storage.objects for select to authenticated
      using(bucket_id='optical-frames' and (storage.foldername(name))[1]=auth.uid()::text)$policy$;
    execute $policy$create policy optical_frame_objects_insert on storage.objects for insert to authenticated
      with check(bucket_id='optical-frames' and (storage.foldername(name))[1]=auth.uid()::text)$policy$;
    execute $policy$create policy optical_frame_objects_update on storage.objects for update to authenticated
      using(bucket_id='optical-frames' and (storage.foldername(name))[1]=auth.uid()::text)
      with check(bucket_id='optical-frames' and (storage.foldername(name))[1]=auth.uid()::text)$policy$;
    execute $policy$create policy optical_frame_objects_delete on storage.objects for delete to authenticated
      using(bucket_id='optical-frames' and (storage.foldername(name))[1]=auth.uid()::text)$policy$;
  end if;
end $$;

commit;

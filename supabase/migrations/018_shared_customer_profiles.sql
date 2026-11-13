-- Shared profiles are independent of order prescription snapshots.
begin;
alter table public.optical_customers add column latest_prescription jsonb;
alter table public.optical_customers add column previous_mobiles jsonb not null default '[]'::jsonb;
alter table public.optical_customers add constraint customer_prescription_object
  check(latest_prescription is null or jsonb_typeof(latest_prescription)='object');
alter table public.optical_customers add constraint customer_mobile_history_array
  check(jsonb_typeof(previous_mobiles)='array');
create unique index optical_customers_unique_phone on public.optical_customers(user_id,normalized_mobile);

create table public.optical_customer_receipts (
  id uuid primary key, user_id uuid not null references auth.users(id) on delete cascade,
  payload jsonb not null, result jsonb not null
);
alter table public.optical_customer_receipts enable row level security;
create policy customer_receipts_owned on public.optical_customer_receipts for select to authenticated using(user_id=auth.uid());
revoke all on public.optical_customer_receipts from public,anon,authenticated;
grant select on public.optical_customer_receipts to authenticated;

create function public.upsert_optical_customer(payload jsonb) returns jsonb
language plpgsql security definer set search_path=public,pg_temp as $$
declare
  owner uuid:=auth.uid(); op uuid:=(payload->>'id')::uuid;
  c jsonb:=payload->'payload'->'customer'; customer_id uuid:=(c->>'id')::uuid;
  current_customer public.optical_customers%rowtype;
  receipt public.optical_customer_receipts%rowtype;
  normalized text:=public.normalize_pk_mobile(c->>'mobile'); aliases jsonb;
  expected bigint:=(payload->>'baseRevision')::bigint;
  stamp bigint:=(extract(epoch from clock_timestamp())*1000)::bigint;
  result jsonb; customer_body jsonb;
begin
  if owner is null then raise exception 'Sign in required' using errcode='42501'; end if;
  if op is null or customer_id is null or expected is null or expected<0
    or payload->>'action' is distinct from 'customer_upsert'
    or payload->'payload'->>'type' is distinct from 'customer_upsert'
    or jsonb_typeof(c) is distinct from 'object' or octet_length(payload::text)>32768
    or length(trim(coalesce(c->>'name',''))) not between 2 and 120
    or normalized is null or c->>'normalizedMobile' is distinct from normalized
    or length(coalesce(c->>'notes',''))>4000
    or (c ? 'latestPrescription' and c->'latestPrescription'<>'null'::jsonb and jsonb_typeof(c->'latestPrescription')<>'object') then
    raise exception 'Invalid customer profile';
  end if;
  perform pg_advisory_xact_lock(hashtextextended(owner::text,81201));
  select * into receipt from public.optical_customer_receipts where id=op;
  if found then
    if receipt.user_id<>owner or receipt.payload<>payload then raise exception 'Operation ID already used'; end if;
    return receipt.result;
  end if;
  select * into current_customer from public.optical_customers where id=customer_id for update;
  if found and current_customer.user_id<>owner then raise exception 'Customer not owned' using errcode='42501'; end if;
  if coalesce(current_customer.revision,0)<>expected then
    result:=jsonb_build_object('accepted',false,'message','Customer changed on another device. Review their latest details before saving.');
  else
    aliases:=coalesce(nullif(c->'previousMobiles','null'::jsonb),'[]'::jsonb);
    if jsonb_typeof(aliases)<>'array' then raise exception 'Invalid mobile history'; end if;
    aliases:=aliases || coalesce(current_customer.previous_mobiles,'[]'::jsonb)
      || case when current_customer.normalized_mobile is not null then jsonb_build_array(current_customer.normalized_mobile) else '[]'::jsonb end;
    select coalesce(jsonb_agg(distinct value),'[]'::jsonb) into aliases from jsonb_array_elements_text(aliases) where value<>normalized;
    if jsonb_array_length(aliases)>100 or exists(select 1 from jsonb_array_elements_text(aliases) where public.normalize_pk_mobile(value) is distinct from value) then
      raise exception 'Invalid mobile history';
    end if;
    if exists(select 1 from public.optical_customers other where other.user_id=owner and other.id<>customer_id
      and (other.normalized_mobile=normalized or other.previous_mobiles ? normalized
        or aliases ? other.normalized_mobile or exists(select 1 from jsonb_array_elements_text(aliases) where other.previous_mobiles ? value))) then
      result:=jsonb_build_object('accepted',false,'message','This mobile number belongs to another customer. No profiles were merged.');
    else
      insert into public.optical_customers(id,user_id,name,mobile,normalized_mobile,alternate_mobile,notes,created_at,updated_at,revision,latest_prescription,previous_mobiles)
      values(customer_id,owner,trim(c->>'name'),trim(c->>'mobile'),normalized,c->>'alternateMobile',coalesce(c->>'notes',''),coalesce(current_customer.created_at,stamp),stamp,expected+1,nullif(c->'latestPrescription','null'::jsonb),aliases)
      on conflict(id) do update set name=excluded.name,mobile=excluded.mobile,normalized_mobile=excluded.normalized_mobile,
        alternate_mobile=excluded.alternate_mobile,notes=excluded.notes,updated_at=excluded.updated_at,revision=excluded.revision,
        latest_prescription=excluded.latest_prescription,previous_mobiles=excluded.previous_mobiles;
      select jsonb_build_object('id',id,'name',name,'mobile',mobile,'normalizedMobile',normalized_mobile,'alternateMobile',alternate_mobile,
        'notes',notes,'createdAt',created_at,'updatedAt',updated_at,'revision',revision,'latestPrescription',latest_prescription,'previousMobiles',previous_mobiles)
        into customer_body from public.optical_customers where id=customer_id;
      result:=jsonb_build_object('accepted',true,'customer',customer_body);
    end if;
  end if;
  insert into public.optical_customer_receipts(id,user_id,payload,result) values(op,owner,payload,result);
  return result;
end $$;
revoke all on function public.upsert_optical_customer(jsonb) from public,anon;
grant execute on function public.upsert_optical_customer(jsonb) to authenticated;
revoke insert,update,delete on public.optical_customers from authenticated;

create function public.optical_customer_page(after_id uuid default '00000000-0000-0000-0000-000000000000') returns setof jsonb
language sql stable security invoker set search_path=public,pg_temp as $$
  select jsonb_build_object('id',id,'name',name,'mobile',mobile,'normalizedMobile',normalized_mobile,'alternateMobile',alternate_mobile,
    'notes',notes,'createdAt',created_at,'updatedAt',updated_at,'revision',revision,'latestPrescription',latest_prescription,'previousMobiles',previous_mobiles)
  from public.optical_customers where user_id=auth.uid() and id>after_id order by id limit 100;
$$;
revoke all on function public.optical_customer_page(uuid) from public,anon;
grant execute on function public.optical_customer_page(uuid) to authenticated;
commit;

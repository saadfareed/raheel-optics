-- Preserve records while replacing the former five-state optical lifecycle.
begin;
alter table public.optical_orders drop constraint optical_orders_check;
alter table public.optical_orders alter column status drop default;
alter type public.optical_order_status rename to optical_order_status_legacy;
create type public.optical_order_status as enum ('active','ready_for_pickup','complete','cancel');
alter table public.optical_orders alter column status type public.optical_order_status using
  (case status::text
    when 'new' then 'active' when 'in_production' then 'active'
    when 'ready' then 'ready_for_pickup' when 'delivered' then 'complete'
    when 'cancelled' then 'cancel' end)::public.optical_order_status;
alter table public.optical_orders alter column status set default 'active';
alter table public.optical_orders add constraint optical_orders_complete_timestamp
  check(status<>'complete' or delivered_at is not null);
drop type public.optical_order_status_legacy;
commit;

-- Support-assisted account-specific increases; retain all history and replay receipts.
begin;
create table public.account_resource_overrides (
    user_id uuid primary key references auth.users(id) on delete cascade,
    max_history_events integer check(max_history_events between 1 and 10000000),
    max_receipts integer check(max_receipts between 1 and 10000000),
    updated_at timestamptz not null default now()
);
alter table public.account_resource_overrides enable row level security;
revoke all on public.account_resource_overrides from public,anon,authenticated;
create or replace function public.apply_operation(payload jsonb) returns jsonb
language plpgsql security definer set search_path=public,pg_temp as $$
declare
    owner uuid := auth.uid();
    operation_id uuid;
    policy public.resource_policy%rowtype;
    admission public.mutation_admission%rowtype;
    totals public.user_totals%rowtype;
    previous public.operation_receipts%rowtype;
    window_time timestamptz := date_trunc('minute',clock_timestamp());
    result jsonb;
begin
    if owner is null then raise exception 'Authentication required' using errcode='42501'; end if;
    if payload is null or jsonb_typeof(payload)<>'object' then raise exception 'Invalid operation'; end if;
    if octet_length(payload::text)>32768 then
        raise sqlstate 'PT413' using message='Operation too large. Local work is retained.';
    end if;
    operation_id := (payload->>'id')::uuid;
    -- Lock once per account, including idempotent and admission decisions.
    insert into public.user_totals(id) values(owner) on conflict do nothing;
    select * into totals from public.user_totals where id=owner for update;
    window_time:=date_trunc('minute',clock_timestamp());
    select * into previous from public.operation_receipts where id=operation_id;
    if found then
        if previous.user_id<>owner then raise exception 'Operation not owned' using errcode='42501'; end if;
        return previous.result;
    end if;
    select p.id,p.operations_per_minute,p.max_tasks,coalesce(o.max_history_events,p.max_history_events),coalesce(o.max_receipts,p.max_receipts)
        into strict policy from public.resource_policy p left join public.account_resource_overrides o on o.user_id=owner where p.id=true;
    insert into public.mutation_admission(user_id,window_start) values(owner,window_time) on conflict do nothing;
    select * into admission from public.mutation_admission where user_id=owner;
    if admission.window_start<>window_time then
        update public.mutation_admission set window_start=window_time,operations=0 where user_id=owner;
        admission.operations:=0;
    end if;
    if admission.operations>=policy.operations_per_minute then
        raise sqlstate 'PT429' using message='Sync rate limit reached. Local work is retained; retry shortly.', detail=jsonb_build_object('category','rate_limit','retryAfterSeconds',greatest(1,ceil(extract(epoch from window_time+interval '1 minute'-clock_timestamp()))::integer),'limit',policy.operations_per_minute,'usage',admission.operations)::text;
    end if;
    if admission.receipts>=policy.max_receipts then
        raise sqlstate 'PT429' using message='Account operation limit reached. Uploads are paused; export device data or contact support. Local work is retained.', detail=jsonb_build_object('category','receipt_quota','limit',policy.max_receipts,'usage',admission.receipts)::text;
    end if;
    if payload->>'action' in ('create','reopen') and totals.active>=policy.max_tasks then
        raise sqlstate 'PT429' using message='Active task limit reached. Uploads are paused until a cloud task slot is available. Local work is retained.', detail=jsonb_build_object('category','task_quota','limit',policy.max_tasks,'usage',totals.active)::text;
    end if;
    if payload->>'action' in ('complete','delete','reopen') and admission.history_events>=policy.max_history_events then
        raise sqlstate 'PT429' using message='Account history limit reached. Uploads are paused; export device data or contact support. Local work is retained.', detail=jsonb_build_object('category','history_quota','limit',policy.max_history_events,'usage',admission.history_events)::text;
    end if;
    result:=public.apply_operation_unchecked(payload);
    update public.mutation_admission set operations=operations+1 where user_id=owner;
    return result;
end $$;
revoke all on function public.apply_operation(jsonb) from public,anon;
grant execute on function public.apply_operation(jsonb) to authenticated;
create or replace function public.account_resource_limits() returns jsonb
language plpgsql stable security definer set search_path=public,pg_temp as $$
begin
    if auth.uid() is null then raise exception 'Authentication required' using errcode='42501'; end if;
    return (select jsonb_build_object('version',1,'taskScope','active','maxActiveTasks',max_tasks,'maxHistoryEvents',coalesce(o.max_history_events,p.max_history_events),'maxReceipts',coalesce(o.max_receipts,p.max_receipts),
        'historyEvents',coalesce((select history_events from public.mutation_admission where user_id=auth.uid()),0),
        'receipts',coalesce((select receipts from public.mutation_admission where user_id=auth.uid()),0))
        from public.resource_policy p left join public.account_resource_overrides o on o.user_id=auth.uid() where p.id=true);
end $$;
revoke all on function public.account_resource_limits() from public,anon;
grant execute on function public.account_resource_limits() to authenticated;notify pgrst, 'reload schema';
commit;

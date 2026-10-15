-- Bounded mutation requests, account admission, and ordered batch receipts.
begin;
-- Quiesce mutation writers before acquiring ledger locks to avoid lock-order inversion.
lock table public.user_totals in share row exclusive mode;
create table public.resource_policy (
    id boolean primary key default true check(id),
    operations_per_minute integer not null default 240 check(operations_per_minute between 1 and 100000),
    max_tasks integer not null default 10000 check(max_tasks between 1 and 1000000),
    max_history_events integer not null default 100000 check(max_history_events between 1 and 10000000),
    max_receipts integer not null default 200000 check(max_receipts between 1 and 10000000)
);
insert into public.resource_policy(id) values(true);
create table public.mutation_admission (
    user_id uuid primary key references auth.users(id) on delete cascade,
    window_start timestamptz not null,
    operations integer not null default 0,
    history_events bigint not null default 0 check(history_events>=0),
    receipts bigint not null default 0 check(receipts>=0)
);
alter table public.resource_policy enable row level security;
alter table public.mutation_admission enable row level security;
revoke all on public.resource_policy,public.mutation_admission from public,anon,authenticated;
-- Seed once under a write-blocking lock; subsequent admission is constant-time.
lock table public.todo_history in share row exclusive mode;
insert into public.mutation_admission(user_id,window_start,history_events)
    select user_id,date_trunc('minute',clock_timestamp()),count(*) from public.todo_history group by user_id;
create function public.capture_history_admission() returns trigger
language plpgsql security definer set search_path=public,pg_temp as $$
begin
    if tg_op='INSERT' then
        insert into public.mutation_admission(user_id,window_start,history_events)
        values(new.user_id,date_trunc('minute',clock_timestamp()),1)
        on conflict(user_id) do update set history_events=mutation_admission.history_events+1;
        return new;
    end if;
    update public.mutation_admission set history_events=greatest(0,history_events-1) where user_id=old.user_id;
    return old;
end $$;
revoke all on function public.capture_history_admission() from public,anon,authenticated;
create trigger history_admission after insert or delete on public.todo_history
for each row execute function public.capture_history_admission();
lock table public.operation_receipts in share row exclusive mode;
insert into public.mutation_admission(user_id,window_start,receipts)
    select user_id,date_trunc('minute',clock_timestamp()),count(*) from public.operation_receipts group by user_id
    on conflict(user_id) do update set receipts=excluded.receipts;
create function public.capture_receipt_admission() returns trigger
language plpgsql security definer set search_path=public,pg_temp as $$
begin
    if tg_op='INSERT' then
        insert into public.mutation_admission(user_id,window_start,receipts)
        values(new.user_id,date_trunc('minute',clock_timestamp()),1)
        on conflict(user_id) do update set receipts=mutation_admission.receipts+1;
        return new;
    end if;
    update public.mutation_admission set receipts=greatest(0,receipts-1) where user_id=old.user_id;
    return old;
end $$;
revoke all on function public.capture_receipt_admission() from public,anon,authenticated;
create trigger receipt_admission after insert or delete on public.operation_receipts
for each row execute function public.capture_receipt_admission();

alter function public.apply_operation(jsonb) rename to apply_operation_unchecked;
revoke all on function public.apply_operation_unchecked(jsonb) from public,anon,authenticated;

create function public.apply_operation(payload jsonb) returns jsonb
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
    select * into strict policy from public.resource_policy where id=true;
    insert into public.mutation_admission(user_id,window_start) values(owner,window_time) on conflict do nothing;
    select * into admission from public.mutation_admission where user_id=owner;
    if admission.window_start<>window_time then
        update public.mutation_admission set window_start=window_time,operations=0 where user_id=owner;
        admission.operations:=0;
    end if;
    if admission.operations>=policy.operations_per_minute then
        raise sqlstate 'PT429' using message='Sync rate limit reached. Local work is retained; retry shortly.';
    end if;
    if admission.receipts>=policy.max_receipts then
        raise sqlstate 'PT429' using message='Account operation limit reached. Contact support; local work is retained.';
    end if;
    if payload->>'action'='create' and totals.active::bigint+totals.completed+totals.deleted>=policy.max_tasks then
        raise sqlstate 'PT429' using message='Account task limit reached. Contact support; local work is retained.';
    end if;
    if payload->>'action' in ('complete','delete','reopen') and admission.history_events>=policy.max_history_events then
        raise sqlstate 'PT429' using message='Account history limit reached. Contact support; local work is retained.';
    end if;
    result:=public.apply_operation_unchecked(payload);
    update public.mutation_admission set operations=operations+1 where user_id=owner;
    return result;
end $$;
revoke all on function public.apply_operation(jsonb) from public,anon;
grant execute on function public.apply_operation(jsonb) to authenticated;

create function public.apply_operations(payloads jsonb) returns jsonb
language plpgsql security definer set search_path=public,pg_temp as $$
declare item jsonb; results jsonb:='[]'::jsonb; result jsonb;
begin
    if auth.uid() is null then raise exception 'Authentication required' using errcode='42501'; end if;
    if payloads is null or jsonb_typeof(payloads)<>'array' then raise exception 'Expected operations array'; end if;
    if jsonb_array_length(payloads) not between 1 and 25 or octet_length(payloads::text)>262144 then
        raise sqlstate 'PT413' using message='Batch too large.';
    end if;
    for item in select value from jsonb_array_elements(payloads) loop
        begin
            result:=public.apply_operation(item);
            results:=results||jsonb_build_array(jsonb_build_object('id',item->>'id','result',result));
        exception
            when sqlstate 'PT429' or sqlstate 'PT413' then
                -- Successful prefix commits; failed suffix stays queued on the client.
                results:=results||jsonb_build_array(jsonb_build_object('id',item->>'id','error',sqlstate,'message',sqlerrm));
                exit;
            when sqlstate 'P0001' or sqlstate '22P02' or sqlstate '22023' then
                results:=results||jsonb_build_array(jsonb_build_object('id',item->>'id','error',sqlstate,'message','Operation could not be validated. Save a corrected copy or keep the remote version.'));
            -- Ownership/auth/database failures abort the whole request, never become a successful rejection.
        end;
    end loop;
    return results;
end $$;
revoke all on function public.apply_operations(jsonb) from public,anon;
grant execute on function public.apply_operations(jsonb) to authenticated;
notify pgrst, 'reload schema';
commit;

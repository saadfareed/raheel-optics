-- Product decision: max_tasks counts active tasks; history/receipt budgets stay separate.
-- Preserve all existing ledgers and operator policy values; no backfill or pruning.
begin;
comment on column public.resource_policy.max_tasks is 'Maximum active tasks per account; create and reopen consume a slot, complete/delete release it.';
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
    if payload->>'action' in ('create','reopen') and totals.active>=policy.max_tasks then
        raise sqlstate 'PT429' using message='Active task limit reached. Complete or delete an active task, then retry sync; local work is retained.';
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

create function public.account_resource_limits() returns jsonb
language plpgsql stable security definer set search_path=public,pg_temp as $$
begin
    if auth.uid() is null then raise exception 'Authentication required' using errcode='42501'; end if;
    return (select jsonb_build_object('version',1,'taskScope','active','maxActiveTasks',max_tasks)
        from public.resource_policy where id=true);
end $$;
revoke all on function public.account_resource_limits() from public,anon;
grant execute on function public.account_resource_limits() to authenticated;
create or replace function public.sync_changes(after_revision bigint default 0,
    after_id uuid default '00000000-0000-0000-0000-000000000000',page_size integer default 100)
returns jsonb language sql stable security invoker set search_path=public,pg_temp as $$
    with rows as (
        select id,body,account_revision from public.todo_sync where user_id=auth.uid()
        and (account_revision,id)>(after_revision,after_id)
        order by account_revision,id limit greatest(1,least(page_size,100))+1
    ), page as (
        select * from rows order by account_revision,id limit greatest(1,least(page_size,100))
    )
    select jsonb_build_object(
        'resource_limits',public.account_resource_limits(),
        'changes',coalesce((select jsonb_agg(to_jsonb(page) order by account_revision,id) from page),'[]'::jsonb),
        'has_more',(select count(*) from rows)>greatest(1,least(page_size,100)),
        'totals',coalesce((select jsonb_build_object('active',active,'completed',completed,'deleted',deleted,'revision',revision)
            from public.user_totals where id=auth.uid()),jsonb_build_object('active',0,'completed',0,'deleted',0,'revision',0)));
$$;
revoke all on function public.sync_changes(bigint,uuid,integer) from public,anon;
grant execute on function public.sync_changes(bigint,uuid,integer) to authenticated;
notify pgrst, 'reload schema';
commit;

-- One bounded FIFO mutation batch plus its canonical delta in one authenticated request.
begin;
create or replace function public.sync_exchange(payloads jsonb default '[]'::jsonb,
    after_revision bigint default 0, after_id uuid default '00000000-0000-0000-0000-000000000000', page_size integer default 100)
returns jsonb language plpgsql security invoker set search_path=public,pg_temp as $$
declare receipts jsonb:='[]'::jsonb; delta jsonb;
begin
    if auth.uid() is null then raise exception 'Authentication required' using errcode='42501'; end if;
    if payloads is null or jsonb_typeof(payloads)<>'array' then raise exception 'Expected operations array'; end if;
    if jsonb_array_length(payloads)>25 or octet_length(payloads::text)>262144 then
        raise sqlstate 'PT413' using message='Batch too large.';
    end if;
    -- Existing mutations hold the account lock until this request commits; no new privileges.
    if jsonb_array_length(payloads)>0 then receipts:=public.apply_operations(payloads); end if;
    delta:=public.sync_changes(after_revision,after_id,page_size);
    return delta || jsonb_build_object('receipts',receipts);
end $$;
revoke all on function public.sync_exchange(jsonb,bigint,uuid,integer) from public,anon;
grant execute on function public.sync_exchange(jsonb,bigint,uuid,integer) to authenticated;
notify pgrst, 'reload schema';
commit;

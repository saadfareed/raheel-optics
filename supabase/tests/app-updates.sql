begin;
-- Migration 024 publishes a real row; this test works on its own copy inside the transaction.
do $$ begin
    if (select message from public.app_update_policy where platform='android') ilike '%daymark%' then raise exception 'Daymark text left in update policy'; end if;
end $$;
delete from public.app_update_policy;
insert into public.app_update_policy(platform, latest_version_code, minimum_version_code, version_name, update_url)
values ('android', 10, 5, '2.0', 'https://example.com/raheel-optics.apk');
set local role anon;
do $$ begin
    if (select count(*) from public.app_update_policy) <> 1 then raise exception 'Signed-out policy unavailable'; end if;
    begin
        update public.app_update_policy set minimum_version_code=10;
        raise exception 'Anonymous write permitted';
    exception when insufficient_privilege then null; end;
end $$;
reset role;
set local role authenticated;
do $$ begin
    if (select count(*) from public.app_update_policy) <> 1 then raise exception 'Signed-in policy unavailable'; end if;
    begin
        delete from public.app_update_policy;
        raise exception 'Authenticated delete permitted';
    exception when insufficient_privilege then null; end;
end $$;
reset role;
do $$ begin
    begin
        update public.app_update_policy set minimum_version_code=11;
        raise exception 'Invalid minimum accepted';
    exception when check_violation then null; end;
    begin
        update public.app_update_policy set update_url='http://example.com/app.apk';
        raise exception 'Insecure URL accepted';
    exception when check_violation then null; end;
end $$;
rollback;

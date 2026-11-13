\set ON_ERROR_STOP on
begin;
insert into public.app_appearance(id,name,tagline) values('default','Raheel Optics test','Clear vision');
set local role anon;
do $$ begin
    if (select name from public.app_appearance where id='default')<>'Raheel Optics test' then
        raise exception 'public brand unavailable';
    end if;
    begin
        update public.app_appearance set name='Changed';
        raise exception 'anonymous brand write accepted';
    exception when insufficient_privilege then null;
    end;
end $$;
set local role authenticated;
do $$ begin
    begin
        delete from public.app_appearance;
        raise exception 'authenticated brand deletion accepted';
    exception when insufficient_privilege then null;
    end;
end $$;
rollback;
\echo 'PASS: public brand reads and administrative-only writes'

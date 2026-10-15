do $$
begin
    if (select event_id from public.todo_history_latest where user_id='33333333-3333-3333-3333-333333333333')
        <> '33333333-0000-0000-0000-000000000003'::uuid then
        raise exception 'Backfill failed revision/time/UUID ordering';
    end if;
    if not exists(select 1 from public.history_analytics_totals
        where user_id='33333333-3333-3333-3333-333333333333' and with_deadline=2 and on_time=1) then
        raise exception 'Backfill failed lifetime totals';
    end if;
end $$;
delete from auth.users where id='33333333-3333-3333-3333-333333333333';
do $$ begin
    if exists(select 1 from public.todo_history_latest where user_id='33333333-3333-3333-3333-333333333333')
       or exists(select 1 from public.history_analytics_totals where user_id='33333333-3333-3333-3333-333333333333') then
        raise exception 'Backfilled data survived account deletion';
    end if;
end $$;

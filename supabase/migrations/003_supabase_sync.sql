-- Incremental, revision-ordered sync. Existing todos/history/receipts stay intact.
begin;
create table if not exists public.todo_sync (
    id uuid primary key references public.todos(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    body jsonb not null,
    account_revision bigint not null
);
create index if not exists todo_sync_owner_revision on public.todo_sync(user_id,account_revision,id);
alter table public.todo_sync enable row level security;
drop policy if exists owned_sync on public.todo_sync;
create policy owned_sync on public.todo_sync for select to authenticated using(user_id=auth.uid());
revoke all on public.todo_sync from anon,authenticated;
grant select on public.todo_sync to authenticated;
-- Each account's mutation lock already serializes this trigger with counter updates.
create or replace function public.capture_todo_sync() returns trigger
language plpgsql security definer set search_path=public,pg_temp as $$
begin
    insert into public.todo_sync(id,user_id,body,account_revision)
    values(new.id,new.user_id,new.body,coalesce((select revision from public.user_totals where id=new.user_id),0)+1)
    on conflict(id) do update set body=excluded.body,account_revision=excluded.account_revision;
    return new;
end $$;
revoke all on function public.capture_todo_sync() from public,anon,authenticated;
drop trigger if exists capture_todo_sync on public.todos;
create trigger capture_todo_sync after insert or update on public.todos for each row execute function public.capture_todo_sync();
-- Legacy rows share a baseline revision; the UUID tie-breaker keeps initial pages complete.
insert into public.todo_sync(id,user_id,body,account_revision)
select t.id,t.user_id,t.body,coalesce(u.revision,0) from public.todos t left join public.user_totals u on u.id=t.user_id
on conflict(id) do nothing;

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
        'changes',coalesce((select jsonb_agg(to_jsonb(page) order by account_revision,id) from page),'[]'::jsonb),
        'has_more',(select count(*) from rows)>greatest(1,least(page_size,100)),
        'totals',coalesce((select jsonb_build_object('active',active,'completed',completed,'deleted',deleted,'revision',revision)
            from public.user_totals where id=auth.uid()),jsonb_build_object('active',0,'completed',0,'deleted',0,'revision',0)));
$$;
revoke all on function public.sync_changes(bigint,uuid,integer) from public,anon;
grant execute on function public.sync_changes(bigint,uuid,integer) to authenticated;
commit;

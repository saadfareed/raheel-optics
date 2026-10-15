-- Public appearance only. Updates require an administrative database role.
begin;
create table if not exists public.app_appearance (
    id text primary key check(id='default'),
    name text not null check(length(name) between 1 and 40),
    tagline text not null check(length(tagline)<=100),
    "logoUrl" text not null default '' check(length("logoUrl")<=1024)
);
alter table public.app_appearance enable row level security;
create policy public_app_appearance on public.app_appearance for select to anon,authenticated using(id='default');
revoke all on public.app_appearance from anon,authenticated;
grant select on public.app_appearance to anon,authenticated;
-- Start with the bundled branding. Insert the singleton when enabling remote branding.
notify pgrst, 'reload schema';
commit;

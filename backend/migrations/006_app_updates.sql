begin;
create table public.app_update_policy (
    platform text primary key check (platform = 'android'),
    latest_version_code integer not null check (latest_version_code > 0),
    minimum_version_code integer not null check (minimum_version_code > 0 and minimum_version_code <= latest_version_code),
    version_name text not null check (length(trim(version_name)) > 0),
    update_url text not null check (update_url ~ '^https://[^/@[:space:]]+([/?#][^[:space:]]*)?$'),
    message text not null default 'A new version of Daymark is available.',
    updated_at timestamptz not null default now()
);
alter table public.app_update_policy enable row level security;
revoke all on public.app_update_policy from public, anon, authenticated;
grant select on public.app_update_policy to anon, authenticated;
create policy read_app_update_policy on public.app_update_policy for select to anon, authenticated using (true);
-- No default release is advertised. Publish only after its download is available.
commit;

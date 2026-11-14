-- Publishes the Android update policy so a broken release can be retired remotely, and replaces the leftover
-- "Daymark" wording. To force everyone off a bad build, raise minimum_version_code (and latest_version_code) to the
-- fixed versionCode once it is live on Google Play:
--   update public.app_update_policy set latest_version_code=10, minimum_version_code=10, version_name='1.5.2',
--     message='This version has a problem. Please update to keep using Raheel Optics.', updated_at=now()
--   where platform='android';
begin;
alter table public.app_update_policy alter column message set default 'A new version of Raheel Optics is available.';
update public.app_update_policy set message='A new version of Raheel Optics is available.' where message ilike '%daymark%';
insert into public.app_update_policy(platform, latest_version_code, minimum_version_code, version_name, update_url)
values ('android', 9, 1, '1.5.1', 'https://play.google.com/store/apps/details?id=pk.raheeloptics.orders')
on conflict (platform) do nothing;
commit;

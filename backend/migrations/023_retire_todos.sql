-- Retire the to-do era. Orders now live only in optical_* (copied by migration 022) and the app no longer calls any
-- of these functions. Tables are MOVED to the private "legacy" schema, not dropped: nothing is lost, and they are no
-- longer reachable through the API. Drop the legacy schema later once you are sure it is not needed.
begin;
create schema if not exists legacy;
revoke all on schema legacy from public,anon,authenticated;

do $$ declare f record; begin
  for f in select p.oid::regprocedure as sig from pg_proc p join pg_namespace n on n.oid=p.pronamespace
    where n.nspname='public' and p.proname in ('apply_operation','apply_operations','history_page','history_latest_page',
      'sync_changes','sync_exchange','analytics_summary','account_resource_limits','capture_todo_sync',
      'capture_history_projections','capture_history_admission','capture_receipt_admission') loop
    execute format('drop function %s cascade',f.sig);
  end loop;
end $$;

do $$ declare t text; begin
  foreach t in array array['todos','active_todos','user_totals','operation_receipts','todo_history','todo_sync',
      'todo_history_latest','history_analytics_totals','mutation_admission'] loop
    if to_regclass('public.'||t) is not null then
      execute format('alter table public.%I set schema legacy',t);
      execute format('revoke all on legacy.%I from public,anon,authenticated',t);
    end if;
  end loop;
end $$;

notify pgrst, 'reload schema';
commit;

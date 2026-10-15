\set ON_ERROR_STOP on
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',true);
do $$
declare p jsonb; result jsonb; page jsonb; task uuid; rev bigint:=0;
    cursor_id uuid:='00000000-0000-0000-0000-000000000000'; seen uuid[]:='{}';
begin
    for i in 1..5 loop
        task:=gen_random_uuid();
        p:=jsonb_build_object('id',gen_random_uuid(),'action','create','baseRevision',0,'occurredAt',1000,
            'todo',jsonb_build_object('id',task,'title','Synced','priority','Medium'));
        result:=public.apply_operation(p);
        if not (result->>'accepted')::boolean then raise exception 'sync create failed'; end if;
    end loop;
    loop
        page:=public.sync_changes(rev,cursor_id,2);
        for p in select * from jsonb_array_elements(page->'changes') loop
            if (p->>'id')::uuid=any(seen) then raise exception 'sync pagination duplicate'; end if;
            seen:=array_append(seen,(p->>'id')::uuid);
            rev:=(p->>'account_revision')::bigint;cursor_id:=(p->>'id')::uuid;
        end loop;
        exit when not (page->>'has_more')::boolean;
    end loop;
    if array_length(seen,1)<>5 or (page->'totals'->>'active')::integer<>5 then raise exception 'sync omitted tasks/totals'; end if;
    task:=seen[1];
    p:=jsonb_build_object('id',gen_random_uuid(),'action','delete','baseRevision',1,'occurredAt',2000,
        'todo',jsonb_build_object('id',task,'title','Synced','priority','Medium'));
    perform public.apply_operation(p);
    page:=public.sync_changes(5,'ffffffff-ffff-ffff-ffff-ffffffffffff',100);
    if jsonb_array_length(page->'changes')<>1 or page->'changes'->0->'body'->>'status'<>'deleted'
        or (page->'totals'->>'revision')::integer<>6 then raise exception 'incremental tombstone missing'; end if;
    if jsonb_array_length(public.sync_changes(6,'ffffffff-ffff-ffff-ffff-ffffffffffff',100)->'changes')<>0 then raise exception 'up-to-date sync returned stale data'; end if;
end $$;
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',true);
do $$ begin
    if jsonb_array_length(public.sync_changes()->'changes')<>0 then raise exception 'sync leaked owned rows'; end if;
end $$;
rollback;
\echo 'PASS: incremental sync, cursor pagination, tombstones, totals and account isolation'

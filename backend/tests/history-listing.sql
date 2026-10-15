\set ON_ERROR_STOP on
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',true);
do $$
declare
    task uuid; op uuid; prev uuid; action text; payload jsonb; result jsonb;
    row jsonb; cursor_time bigint:=9223372036854775807;
    cursor_id uuid:='ffffffff-ffff-ffff-ffff-ffffffffffff'; seen uuid[]:='{}';
begin
    for i in 1..3 loop
        task:=gen_random_uuid(); prev:=null;
        foreach action in array array['create','complete','reopen','complete','delete'] loop
            op:=gen_random_uuid();
            payload:=jsonb_build_object('id',op,'action',action,'baseRevision',0,'previousOperation',prev,'occurredAt',5000,
                'todo',jsonb_build_object('id',task,'title','Cycle','priority','Medium','notes','Retained'));
            result:=public.apply_operation(payload);
            if not (result->>'accepted')::boolean then raise exception 'cycle transition rejected: %',action; end if;
            prev:=op;
        end loop;
    end loop;
    if (select count(*) from public.todo_history)<>12 then raise exception 'event ledger was modified'; end if;
    if (select count(*) from public.history_latest_page())<>3 then raise exception 'list contains duplicate todos'; end if;
    loop
        select * into row from public.history_latest_page(cursor_time,cursor_id,1);
        exit when row is null;
        cursor_time:=(row->>'occurredAt')::bigint;
        cursor_id:=(row->'todo'->>'id')::uuid;
        if cursor_id=any(seen) then raise exception 'pagination repeated a todo'; end if;
        if row->>'action'<>'delete' or (row->'todo'->>'revision')::bigint<>5 then raise exception 'list did not use latest revision'; end if;
        seen:=array_append(seen,cursor_id);
    end loop;
    if array_length(seen,1)<>3 then raise exception 'pagination omitted todos'; end if;
end $$;
select set_config('request.jwt.claim.sub','22222222-2222-2222-2222-222222222222',true);
do $$ begin
    if exists(select 1 from public.history_latest_page()) then raise exception 'history list leaked across accounts'; end if;
end $$;
rollback;
\echo 'PASS: latest history, repeated cycles, equal-time pagination and ownership'

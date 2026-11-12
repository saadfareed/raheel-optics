-- Per-action order RPCs: optical_order_status and optical_payment_add.
-- Both are thin wrappers over apply_optical_order so validation, the ledger rules and the
-- audit events live in one place. They apply to the order's CURRENT revision (no stale-base
-- rejection): a status change or a payment is a delta, not a whole-order overwrite, and the
-- status machine / payment-total checks still reject anything illegal.
--
-- optical_order_status(payload): {id, action:'order_status', occurredAt, orderId, status, deliveredAt?}
-- optical_payment_add(payload):  {id, action:'payment_add'|'payment_refund', occurredAt, orderId,
--                                 payment:{id, amountPaisa>0, method, receivedAt, note}}
-- Result: apply_optical_order's {accepted, order|message}. A refund sends a positive amount;
-- the wrapper signs it. A missing/unowned order returns accepted=false (never leaks existence).

begin;

create function public.optical_order_mutate(payload jsonb, kind text) returns jsonb
language plpgsql security definer set search_path=public,pg_temp as $$
declare
  owner uuid:=auth.uid(); op uuid; oid uuid; cur jsonb; receipt public.optical_order_receipts%rowtype;
  inner_id uuid; st text; pay jsonb; amount bigint; signed bigint; occurred bigint; result jsonb; next jsonb;
  uuid_re constant text:='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';
begin
  if owner is null then raise exception 'Sign in required' using errcode='42501'; end if;
  if payload is null or jsonb_typeof(payload)<>'object' then raise exception 'Invalid operation'; end if;
  if octet_length(payload::text)>16384 then raise sqlstate 'PT413' using message='Operation too large. Local work is retained.'; end if;
  if coalesce(payload->>'id','')!~* uuid_re or coalesce(payload->>'orderId','')!~* uuid_re
     or coalesce(payload->>'occurredAt','')!~'^[0-9]{1,15}$' then raise exception 'Invalid order operation'; end if;
  op:=(payload->>'id')::uuid; oid:=(payload->>'orderId')::uuid; occurred:=(payload->>'occurredAt')::bigint;

  -- Same lock apply_optical_order takes (re-entrant) so read-modify-write cannot interleave.
  perform pg_advisory_xact_lock(hashtextextended(owner::text,81202));
  select * into receipt from public.optical_order_receipts where id=op;
  if found then
    if receipt.user_id<>owner or receipt.payload<>payload then raise exception 'Operation ID already used'; end if;
    return receipt.result;
  end if;

  if kind='status' then
    if payload->>'action'<>'order_status' then raise exception 'Invalid order operation'; end if;
    st:=upper(coalesce(payload->>'status',''));
    if st not in ('ACTIVE','READY_FOR_PICKUP','COMPLETE','CANCEL') then raise exception 'Invalid order status'; end if;
    if payload ? 'deliveredAt' and payload->'deliveredAt'<>'null'::jsonb
       and (jsonb_typeof(payload->'deliveredAt')<>'number' or (payload->>'deliveredAt')!~'^[0-9]{1,15}$') then
      raise exception 'Invalid order date'; end if;
  else
    if payload->>'action' not in ('payment_add','payment_refund') then raise exception 'Invalid order operation'; end if;
    pay:=payload->'payment';
    if jsonb_typeof(pay) is distinct from 'object' or coalesce(pay->>'id','')!~* uuid_re
       or coalesce(pay->>'amountPaisa','')!~'^[1-9][0-9]{0,11}$' or coalesce(pay->>'receivedAt','')!~'^[0-9]{1,15}$'
       or length(coalesce(pay->>'note',''))>1000
       or lower(coalesce(pay->>'method','cash')) not in ('cash','card','bank_transfer','jazzcash','easypaisa','other') then
      raise exception 'Invalid payment'; end if;
    amount:=(pay->>'amountPaisa')::bigint;
    signed:=case when payload->>'action'='payment_refund' then -amount else amount end;
  end if;

  cur:=public.optical_order_json(oid);
  if cur is null or not exists(select 1 from public.optical_orders where id=oid and user_id=owner) then
    result:=jsonb_build_object('accepted',false,'message','Order not found on the server.','order',null);
  else
    if kind='status' then
      next:=cur||jsonb_build_object('status',st);
      if payload ? 'deliveredAt' then next:=next||jsonb_build_object('deliveredAt',payload->'deliveredAt'); end if;
    else
      next:=cur||jsonb_build_object('payments',(cur->'payments')||jsonb_build_array(jsonb_build_object(
        'id',pay->>'id','amountPaisa',signed,'method',lower(coalesce(pay->>'method','cash')),
        'receivedAt',(pay->>'receivedAt')::bigint,'note',coalesce(pay->>'note',''))));
    end if;
    -- The inner operation id is derived so the wrapper's own receipt (below) is the replay record.
    inner_id:=md5(op::text||':inner')::uuid;
    result:=public.apply_optical_order(jsonb_build_object('id',inner_id,'action','order_edit',
      'baseRevision',(cur->>'revision')::bigint,'occurredAt',occurred,'order',next));
  end if;
  insert into public.optical_order_receipts(id,user_id,payload,result) values(op,owner,payload,result);
  return result;
end $$;
revoke all on function public.optical_order_mutate(jsonb,text) from public,anon,authenticated;

create function public.optical_order_status(payload jsonb) returns jsonb
language sql security definer set search_path=public,pg_temp as $$
  select public.optical_order_mutate(payload,'status') $$;
revoke all on function public.optical_order_status(jsonb) from public,anon;
grant execute on function public.optical_order_status(jsonb) to authenticated;

create function public.optical_payment_add(payload jsonb) returns jsonb
language sql security definer set search_path=public,pg_temp as $$
  select public.optical_order_mutate(payload,'payment') $$;
revoke all on function public.optical_payment_add(jsonb) from public,anon;
grant execute on function public.optical_payment_add(jsonb) to authenticated;

create or replace function public.optical_orders_api_version() returns integer
language sql stable set search_path=public,pg_temp as $$select 2$$;
notify pgrst, 'reload schema';
commit;

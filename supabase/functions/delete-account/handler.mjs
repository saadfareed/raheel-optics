// No credentials or request bodies are logged. Admin credentials stay on the server.
async function boundedBody(request, maxBytes = 8192, timeoutMs = 10000) {
  const declared = request.headers.get('content-length');
  if (declared && (!/^\d+$/.test(declared) || Number(declared) > maxBytes))
    throw Object.assign(new Error('Request too large.'), { status: 413 });
  if (!request.body) throw Object.assign(new Error('Enter your current password.'), { status: 400 });
  const reader = request.body.getReader();
  const chunks = []; let bytes = 0; let timer;
  const deadline = new Promise((_, reject) => {
    timer = setTimeout(() => { reject(Object.assign(new Error('Request timed out.'), { status: 408 })); reader.cancel().catch(() => {}); }, timeoutMs);
  });
  try {
    while (true) {
      const { done, value } = await Promise.race([reader.read(), deadline]);
      if (done) break;
      bytes += value.byteLength;
      if (bytes > maxBytes) {
        reader.cancel().catch(() => {});
        throw Object.assign(new Error('Request too large.'), { status: 413 });
      }
      chunks.push(value);
    }
    const body = new Uint8Array(bytes); let offset = 0;
    for (const chunk of chunks) { body.set(chunk, offset); offset += chunk.byteLength; }
    try { return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(body)); }
    catch { throw Object.assign(new Error('Invalid request.'), { status: 400 }); }
  } finally { clearTimeout(timer); reader.releaseLock(); }
}

export function deletionHandler({ url, publicKey, adminKey, fetcher = fetch, bodyTimeoutMs = 10000 }) {
  if (!(bodyTimeoutMs > 0 && bodyTimeoutMs <= 10000)) throw new Error('Invalid request timeout.');
  const headers = { 'Content-Type': 'application/json', 'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'authorization, apikey, content-type',
    'Access-Control-Allow-Methods': 'POST, OPTIONS' };
  const reply = (status, message) => new Response(JSON.stringify({ message }), { status, headers });
  return async request => {
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers });
    if (request.method !== 'POST') return reply(405, 'Use POST.');
    if (!url || !publicKey || !adminKey) return reply(503, 'Deletion is temporarily unavailable.');
    const token = request.headers.get('authorization');
    if (!token?.startsWith('Bearer ')) return reply(401, 'Sign in first.');
    let proofToken; let accountDeleted=false;
    try {
      const body = await boundedBody(request, 8192, bodyTimeoutMs);
      if (!body || typeof body.current_password !== 'string' || !body.current_password || body.current_password.length > 4096)
        return reply(400, 'Enter your current password.');
      const call = (path, method, key, authorization, data) => fetcher(`${url}/auth/v1/${path}`, {
        method, headers: { apikey: key, ...(authorization ? { Authorization: authorization } : {}), 'Content-Type': 'application/json' },
        body: data ? JSON.stringify(data) : undefined, signal: AbortSignal.timeout(15000), redirect: 'error'
      });
      const identity = await call('user', 'GET', publicKey, token);
      if (!identity.ok) return reply(401, 'Sign in again.');
      const user = await identity.json();
      if (!user.id || !user.email) return reply(403, 'This account cannot use password confirmation.');
      const proof = await call('token?grant_type=password', 'POST', publicKey, undefined,
        { email: user.email, password: body.current_password });
      if (!proof.ok) return reply(403, 'Password confirmation failed.');
      const verified = await proof.json();
      proofToken=verified.access_token;
      if (verified.user?.id !== user.id || !verified.access_token) return reply(403, 'Account confirmation failed.');
      const deleted = await call(`admin/users/${encodeURIComponent(user.id)}`, 'DELETE', adminKey, `Bearer ${adminKey}`);
      if (!deleted.ok) return reply(503, 'Deletion failed. Try again.');
      accountDeleted=true;
      return reply(200, 'Account deleted.');
    } catch (error) {
      if ([400, 408, 413].includes(error.status)) return reply(error.status, error.message);
      return reply(503, 'Unable to confirm deletion. Check your connection and try again.');
    } finally {
      if(proofToken && !accountDeleted) {
        try {await fetcher(`${url}/auth/v1/logout?scope=local`,{method:'POST',headers:{apikey:publicKey,Authorization:`Bearer ${proofToken}`},signal:AbortSignal.timeout(5000),redirect:'error'});}
        catch(_) { /* Temporary proof session cleanup is best effort on provider/network failure. */ }
      }
    }
  };
}

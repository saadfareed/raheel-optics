import { deletionHandler } from './handler.mjs';
Deno.serve(deletionHandler({
  url: Deno.env.get('SUPABASE_URL'),
  publicKey: Deno.env.get('SUPABASE_ANON_KEY'),
  adminKey: Deno.env.get('SUPABASE_SERVICE_ROLE_KEY'),
}));

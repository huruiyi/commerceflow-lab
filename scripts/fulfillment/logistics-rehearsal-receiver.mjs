import { createServer } from 'node:http';
import { createHmac } from 'node:crypto';
import { existsSync } from 'node:fs';

// stdout is the receipt log of this isolated consumer. Each accepted POST must have a valid signature.
createServer(async (request, response) => {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  const body = Buffer.concat(chunks);
  const signature = createHmac('sha256', process.env.CALLBACK_SECRET).update(body).digest('base64');
  if (request.method !== 'POST' || request.url !== '/callback' ||
      signature !== request.headers['x-callback-signature']) {
    response.writeHead(400).end();
    return;
  }
  const {notificationId, merchantShipmentNo, status} = JSON.parse(body);
  if (existsSync('/tmp/reject-callbacks')) {
    response.writeHead(503).end();
    return;
  }
  console.log(JSON.stringify({notificationId, merchantShipmentNo, status}));
  response.writeHead(204).end();
}).listen(8080);

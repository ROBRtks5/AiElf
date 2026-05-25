const https = require('https');

const data = JSON.stringify({
  contents: [{ parts: [{ text: "Hello" }] }]
});

const options = {
  hostname: 'generativelanguage.googleapis.com',
  port: 443,
  path: '/v1beta/models/gemini-1.5-flash-latest:streamGenerateContent?alt=sse&key=YOUR_API_KEY',
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Content-Length': data.length
  }
};

const req = https.request(options, res => {
  console.log(`-latest statusCode: ${res.statusCode}`);
});

req.on('error', error => {
  console.error(error);
});

req.write(data);
req.end();

const options2 = {
  hostname: 'generativelanguage.googleapis.com',
  port: 443,
  path: '/v1beta/models/gemini-1.5-flash:streamGenerateContent?alt=sse&key=YOUR_API_KEY',
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Content-Length': data.length
  }
};

const req2 = https.request(options2, res => {
  console.log(`normal statusCode: ${res.statusCode}`);
});
req2.on('error', error => {
  console.error(error);
});
req2.write(data);
req2.end();


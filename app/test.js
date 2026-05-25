const https = require('https');

const data = JSON.stringify({
  contents: [{ parts: [{ text: "Hello" }] }]
});

const options = {
  hostname: 'generativelanguage.googleapis.com',
  port: 443,
  path: '/v1beta/models/gemini-1.5-flash:streamGenerateContent?alt=sse&key=YOUR_API_KEY',
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Content-Length': data.length
  }
};

const req = https.request(options, res => {
  console.log(`statusCode: ${res.statusCode}`);
});

req.on('error', error => {
  console.error(error);
});

req.write(data);
req.end();

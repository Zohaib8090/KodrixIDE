const https = require('https');
const net = require('net');

const port = parseInt(process.argv[2] || '3000');

function getInfo(retry = 0) {
    const options = {
        hostname: 'localtunnel.me',
        path: '/?new',
        headers: { 'User-Agent': 'node' },
        timeout: 10000
    };

    const req = https.get(options, (res) => {
        let data = '';
        res.on('data', d => data += d.toString());
        res.on('end', () => {
            try {
                const info = JSON.parse(data);
                if (info.url) {
                    startTunnel(info);
                } else {
                    throw new Error('No URL in response');
                }
            } catch (e) {
                if (retry < 5) setTimeout(() => getInfo(retry + 1), 2000);
                else console.error('Failed to parse tunnel info:', data);
            }
        });
    });

    req.on('error', (e) => {
        if (retry < 5) setTimeout(() => getInfo(retry + 1), 2000);
        else console.error('Tunnel info fetch failed:', e.message);
    });
    req.end();
}

function startTunnel(info) {
    console.log('your url is: ' + info.url);
    const remoteHost = new URL(info.url).hostname;
    const remotePort = info.port;
    const maxConns = info.max_conn_count || 10;

    for (let i = 0; i < maxConns; i++) {
        makeConnection(remoteHost, remotePort);
    }
}

function makeConnection(host, rPort) {
    const remote = net.connect(rPort, host);
    
    remote.on('connect', () => {
        const local = net.connect(port, '127.0.0.1');
        local.on('connect', () => {
            remote.pipe(local);
            local.pipe(remote);
        });
        local.on('error', (err) => {
            remote.destroy();
        });
    });

    remote.on('error', () => {
        setTimeout(() => makeConnection(host, rPort), 3000);
    });

    remote.on('close', () => {
        setTimeout(() => makeConnection(host, rPort), 3000);
    });
}

getInfo();
setInterval(() => {}, 100000);


const express = require('express');
const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
const cors = require('cors');

puppeteer.use(StealthPlugin());
const app = express();
app.use(cors());
app.use(express.json());

let browser;
const CHROME_PATH = process.env.CHROME_PATH || '/usr/bin/chromium-browser';
const USER_DATA_DIR = process.env.USER_DATA_DIR || './user_data';

async function startBrowser(show = false) {
    console.log(`--- AI Engine Launching (Headless: ${!show}) ---`);
    if (browser) await browser.close();
    
    try {
        browser = await puppeteer.launch({
            executablePath: CHROME_PATH,
            headless: !show ? "new" : false,
            userDataDir: USER_DATA_DIR,
            args: [
                '--no-sandbox',
                '--disable-setuid-sandbox',
                '--disable-gpu',
                '--disable-dev-shm-usage',
                '--single-process',
                '--no-zygote'
            ]
        });
        console.log("STATUS: AI Engine Ready.");
    } catch (err) {
        console.error("LAUNCH ERROR:", err.message);
    }
}

app.post('/ask', async (req, res) => {
    const { prompt } = req.body;
    if (!browser) return res.status(500).json({ error: "AI Engine not started" });

    let page;
    try {
        page = await browser.newPage();
        
        // RAM SHIELD
        await page.setRequestInterception(true);
        page.on('request', (request) => {
            const rType = request.resourceType();
            if (['image', 'stylesheet', 'font', 'media'].includes(rType)) {
                request.abort();
            } else {
                request.continue();
            }
        });

        await page.goto('https://gemini.google.com/app', { waitUntil: 'networkidle2', timeout: 60000 });

        const inputSelector = 'div[role="textbox"]';
        const isLoggedIn = await page.$(inputSelector) !== null;
        if (!isLoggedIn) {
            return res.status(401).json({ status: "needs_login", message: "Please log in to Gemini first." });
        }

        await page.type(inputSelector, prompt);
        await page.keyboard.press('Enter');

        const outputSelector = '.message-content, .model-response-text';
        await page.waitForSelector(outputSelector, { timeout: 60000 });
        
        await page.waitForFunction((sel) => {
            const nodes = document.querySelectorAll(sel);
            const last = nodes[nodes.length - 1];
            return last && last.innerText.length > 0 && !last.innerText.endsWith('...');
        }, { polling: 1000, timeout: 30000 }, outputSelector);

        const response = await page.$$eval(outputSelector, nodes => nodes[nodes.length - 1].innerText);
        res.json({ status: "success", data: response });
    } catch (err) {
        res.status(500).json({ status: "error", message: err.message });
    } finally {
        if (page) await page.close();
    }
});

app.post('/restart', async (req, res) => {
    const { show } = req.body;
    await startBrowser(show);
    res.json({ status: "restarting" });
});

const PORT = process.env.PORT || 9999;
app.listen(PORT, '127.0.0.1', () => {
    console.log(`AI Backend running on port ${PORT}`);
    startBrowser(false);
});

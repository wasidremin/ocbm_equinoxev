import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'tests',use:{baseURL:process.env.EVINSIGHTS_URL||'http://127.0.0.1:8088',headless:true,launchOptions:process.env.CHROME_PATH?{executablePath:process.env.CHROME_PATH}:undefined},workers:1,retries:0});

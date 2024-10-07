import { Octokit } from '@octokit/rest';
import axios from 'axios';
import { execSync } from 'child_process';
import dotenv from 'dotenv';
import fs from 'fs';

// Load environment variables from .env file
dotenv.config('.env');

// Function to log info messages
const logInfo = (message) => {
  console.log(`[INFO] ${message}`);
};

// Read the index.html file
logInfo('Reading index.html file');
const indexHtml = fs.readFileSync('index.html', 'utf-8');

// Extract all href URLs from index.html
logInfo('Extracting URLs from index.html');
const urls = indexHtml.match(/href="([^"]+)"/g).map(href => href.slice(6, -1));

// Filter URLs that start with "https://github.com/turnerturn/"
const githubProjectUrls = urls.filter(url => url.startsWith(process.env.GITHUB_PROFILE_URL));

// Initialize Octokit
const octokit = new Octokit({
  auth: process.env.GITHUB_TOKEN // Use GitHub token from .env file
});

// Loop through each GitHub URL
githubProjectUrls.forEach(async (url) => {
  logInfo(`Checking URL: ${url}`);
  
  try {
    // Use axios to check if the URL returns a 404 status code
    await axios.get(url);
    logInfo(`URL exists: ${url}`);
  } catch (error) {
    if (error.response && error.response.status === 404) {
      logInfo(`URL not found (404): ${url}`);
      
      // Extract the repository name from the URL
      const repoName = url.split('/').pop();
      
      // Create a new directory and navigate into it
      logInfo(`Creating repository directory: ${repoName}`);
      fs.mkdirSync(repoName);
      process.chdir(repoName);
      
      // Initialize a new git repository
      logInfo('Initializing git repository');
      execSync('git init');
      
      // Create a README.md file with a basic starter template
      logInfo('Creating README.md');
      fs.writeFileSync('README.md', `# ${repoName}`);
      
      // Create a LICENSE file with the MIT License template
      logInfo('Creating LICENSE file');
      const licenseContent = `MIT License

Copyright (c) ${new Date().getFullYear()} [Your Name]

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.`;
      fs.writeFileSync('LICENSE', licenseContent);
      
      // Add and commit the files to the local repository
      logInfo('Adding and committing files');
      execSync('git add README.md LICENSE');
      execSync('git commit -m "Initial commit with README and MIT License"');
      
      // Create a new repository on GitHub using Octokit
      logInfo(`Creating GitHub repository: ${repoName}`);
      await octokit.repos.createForAuthenticatedUser({
        name: repoName,
        private: false
      });
      
      // Add remote and push the local repository to GitHub
      logInfo('Pushing repository to GitHub');
      execSync(`git remote add origin https://github.com/turnerturn/${repoName}.git`);
      execSync('git push -u origin main');
      
      logInfo(`Repository '${repoName}' created and pushed to GitHub successfully.`);
      
      // Navigate back to the parent directory
      process.chdir('..');
    } else {
      logInfo(`Error checking URL: ${url}`);
    }
  }
});

logInfo('Script execution completed.');
/**
 * Forge Bridge — Claude browser-tier injector
 * Injected into claude.ai WebView context via evaluateJavascript().
 * Makes fetch() calls same-origin so cookies/auth are automatic.
 *
 * Placeholders replaced by BrowserProviderManager before injection:
 *   %MESSAGE% → escaped user message
 *   %MODEL%   → model string (or empty for default)
 */
(async function forgeSend() {
    const message = '%MESSAGE%';
    const model   = '%MODEL%' || 'claude-sonnet-4-5';

    window.ForgeBridge.log('Claude injector starting, model=' + model);

    // Step 1: get org ID
    let orgs;
    try {
        const resp = await fetch('/api/organizations', { credentials: 'include' });
        if (!resp.ok) {
            window.ForgeBridge.onError('Could not get org list — HTTP ' + resp.status +
                (resp.status === 401 || resp.status === 403 ? ' — session expired, re-login' : ''));
            return;
        }
        orgs = await resp.json();
    } catch (e) {
        window.ForgeBridge.onError('Network error getting org: ' + e.message);
        return;
    }

    const orgId = orgs && orgs[0] && orgs[0].uuid;
    if (!orgId) {
        window.ForgeBridge.onError('No organization found in Claude account');
        return;
    }
    window.ForgeBridge.log('org=' + orgId);

    // Step 2: create conversation
    const convId = crypto.randomUUID();
    try {
        const resp = await fetch('/api/organizations/' + orgId + '/chat_conversations', {
            method: 'POST',
            credentials: 'include',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ uuid: convId, name: '', model: model })
        });
        if (!resp.ok) {
            window.ForgeBridge.onError('Could not create conversation — HTTP ' + resp.status);
            return;
        }
    } catch (e) {
        window.ForgeBridge.onError('Network error creating conversation: ' + e.message);
        return;
    }
    window.ForgeBridge.log('conv=' + convId);

    // Step 3: stream completion
    let compResp;
    try {
        compResp = await fetch(
            '/api/organizations/' + orgId + '/chat_conversations/' + convId + '/completion',
            {
                method: 'POST',
                credentials: 'include',
                headers: {
                    'Content-Type': 'application/json',
                    'Accept': 'text/event-stream',
                },
                body: JSON.stringify({
                    prompt: message,
                    model: model,
                    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
                    attachments: [],
                    files: []
                })
            }
        );
    } catch (e) {
        window.ForgeBridge.onError('Network error: ' + e.message);
        return;
    }

    if (!compResp.ok) {
        window.ForgeBridge.onError('Claude completion HTTP ' + compResp.status);
        return;
    }

    const reader  = compResp.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    try {
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop();

            for (const line of lines) {
                if (!line.startsWith('data: ')) continue;
                const raw = line.slice(6).trim();
                if (!raw) continue;

                let obj;
                try { obj = JSON.parse(raw); } catch (_) { continue; }

                switch (obj.type) {
                    case 'completion':
                        if (obj.completion) window.ForgeBridge.onChunk(obj.completion);
                        if (obj.stop_reason && obj.stop_reason !== 'null') {
                            window.ForgeBridge.onDone(); return;
                        }
                        break;
                    case 'error':
                        window.ForgeBridge.onError(
                            (obj.error && obj.error.message) || 'Claude stream error');
                        return;
                    case 'ping':
                    case 'message_limit':
                        break;
                }
            }
        }
    } catch (e) {
        window.ForgeBridge.onError('Stream read error: ' + e.message);
        return;
    }

    window.ForgeBridge.onDone();
})();

/**
 * Forge Bridge — ChatGPT browser-tier injector
 * Injected into chatgpt.com WebView context via evaluateJavascript().
 * Makes fetch() calls same-origin so cookies/auth are automatic.
 *
 * Placeholders replaced by BrowserProviderManager before injection:
 *   %MESSAGE% → escaped user message
 *   %MODEL%   → model string (or empty for default)
 */
(async function forgeSend() {
    const message = '%MESSAGE%';
    const model   = '%MODEL%' || 'gpt-4o';

    window.ForgeBridge.log('ChatGPT injector starting, model=' + model);

    const parentId = crypto.randomUUID();
    const body = JSON.stringify({
        action: 'next',
        messages: [{
            id: crypto.randomUUID(),
            author: { role: 'user' },
            content: { content_type: 'text', parts: [message] },
            metadata: {}
        }],
        parent_message_id: parentId,
        model: model,
        timezone_offset_min: new Date().getTimezoneOffset(),
        conversation_mode: { kind: 'primary_assistant' },
        force_use_sse: true,
        history_and_training_disabled: false
    });

    let resp;
    try {
        resp = await fetch('/backend-api/conversation', {
            method: 'POST',
            credentials: 'include',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'text/event-stream',
            },
            body
        });
    } catch (e) {
        window.ForgeBridge.onError('Network error: ' + e.message);
        return;
    }

    if (!resp.ok) {
        window.ForgeBridge.onError('ChatGPT HTTP ' + resp.status +
            (resp.status === 401 || resp.status === 403 ? ' — session expired, re-login' : ''));
        return;
    }

    const reader  = resp.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let lastContent = '';
    let buffer = '';

    try {
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop(); // keep incomplete last line

            for (const line of lines) {
                if (!line.startsWith('data: ')) continue;
                const raw = line.slice(6).trim();
                if (raw === '[DONE]') { window.ForgeBridge.onDone(); return; }
                if (!raw) continue;

                let obj;
                try { obj = JSON.parse(raw); } catch (_) { continue; }

                if (obj.error) {
                    window.ForgeBridge.onError('ChatGPT stream error: ' + JSON.stringify(obj.error));
                    return;
                }

                // ChatGPT sends accumulated content — compute delta
                const parts = obj?.message?.content?.parts;
                if (!Array.isArray(parts)) continue;
                const fullContent = parts.join('');
                const delta = fullContent.slice(lastContent.length);
                lastContent = fullContent;
                if (delta) window.ForgeBridge.onChunk(delta);
            }
        }
    } catch (e) {
        window.ForgeBridge.onError('Stream read error: ' + e.message);
        return;
    }

    window.ForgeBridge.onDone();
})();

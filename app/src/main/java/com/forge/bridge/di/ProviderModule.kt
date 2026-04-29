package com.forge.bridge.di

import com.forge.bridge.data.remote.providers.BaseProvider
import com.forge.bridge.data.remote.providers.anthropic.AnthropicAdapter
import com.forge.bridge.data.remote.providers.automation.BrowserAutomationProvider
import com.forge.bridge.data.remote.providers.chatgpt.ChatGptProxyAdapter
import com.forge.bridge.data.remote.providers.claude.ClaudeProxyAdapter
import com.forge.bridge.data.remote.providers.openai.OpenAiAdapter
import com.forge.bridge.data.remote.providers.gemini.GeminiAdapter
import com.forge.bridge.data.remote.providers.gemini.GeminiProxyAdapter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
@InstallIn(SingletonComponent::class)
abstract class ProviderModule {

    @Binds
    @IntoMap
    @StringKey("openai")
    abstract fun bindOpenAi(adapter: OpenAiAdapter): BaseProvider

    @Binds
    @IntoMap
    @StringKey("anthropic")
    abstract fun bindAnthropic(adapter: AnthropicAdapter): BaseProvider

    @Binds
    @IntoMap
    @StringKey("chatgpt-proxy")
    abstract fun bindChatGptProxy(adapter: ChatGptProxyAdapter): BaseProvider

    @Binds
    @IntoMap
    @StringKey("claude-proxy")
    abstract fun bindClaudeProxy(adapter: ClaudeProxyAdapter): BaseProvider

    @Binds
    @IntoMap
    @StringKey("browser-tier")
    abstract fun bindBrowserAutomation(adapter: BrowserAutomationProvider): BaseProvider

    @Binds
    @IntoMap
    @StringKey("gemini")
    abstract fun bindGemini(adapter: GeminiAdapter): BaseProvider

    @Binds
    @IntoMap
    @StringKey("gemini-proxy")
    abstract fun bindGeminiProxy(adapter: GeminiProxyAdapter): BaseProvider
}

# JupyterLab AI-Powered IDE Features

This JupyterLab environment is configured as a full-featured AI-powered development IDE with intelligent code assistance, version control, and LLM integration.

## 🤖 AI-Powered Features

### 1. Jupyter AI - Native LLM Integration

**AI Chat Sidebar**
- Click the chat icon in the left sidebar to open the AI assistant
- Ask questions about code, get explanations, or request code generation
- Pre-configured to use LiteLLM endpoint (`http://litellm:4000/v1`)
- Model: `qwen2.5-0.5b`

**Magic Commands**
Use `%%ai` in any cell for inline AI assistance:

```python
%%ai openai-chat:qwen2.5-0.5b
Write a Python function to calculate the Fibonacci sequence
```

**Features:**
- Code generation in any language (Python, JavaScript, Kotlin)
- Code explanation and documentation
- Debugging assistance
- Algorithm optimization suggestions
- Natural language to code translation

### 2. Language Server Protocol (LSP) - Intelligent Code Completion

**Features:**
- **Smart Autocompletion**: Context-aware code suggestions as you type
- **Hover Documentation**: Hover over functions/variables to see documentation
- **Error Diagnostics**: Real-time syntax and semantic error detection
- **Jump to Definition**: Alt+Click on any symbol to navigate to its definition
- **Find References**: See all usages of a function or variable
- **Code Navigation**: Jump back with Alt+O

**Supported Languages:**
- Python (via python-lsp-server + jedi)
- JavaScript/TypeScript (via typescript-language-server)
- Kotlin (kernel-based completion)

**Configuration:**
- Continuous hinting enabled by default
- Diagnostics panel shows all errors/warnings
- Works alongside kernel-based completion

### 3. Git Integration - Version Control in IDE

**Features:**
- Visual Git interface in the left sidebar
- Stage, commit, and push changes directly from JupyterLab
- View diff of changes before committing
- Branch management and switching
- Clone repositories from within the IDE

**Usage:**
1. Click the Git icon in the left sidebar
2. Initialize repository or open existing one
3. Stage files by clicking the '+' icon
4. Write commit message and commit
5. Push/pull from remote repositories

### 4. Code Formatting - Auto-format Code

**Keyboard Shortcuts:**
- Format cell: `Ctrl+Shift+I` (or `Cmd+Shift+I` on Mac)
- Format entire notebook: Right-click → Format Notebook

**Formatters Included:**
- **Black**: Python code formatter
- **isort**: Python import organizer

**Auto-format on Save:**
Can be configured in Settings → Code Formatter

### 5. Additional IDE Productivity Features

**Vim Mode** (`jupyterlab-vim`)
- Enable Vim keybindings for navigation and editing
- Toggle in Settings → Keyboard Shortcuts

**Execute Time** (`jupyterlab-execute-time`)
- Shows execution time for each cell
- Helps identify slow-running code

**Resource Usage** (`jupyter-resource-usage`)
- Real-time CPU and memory monitoring
- Displays in the status bar

## 🎯 Quick Start Guide

### Using the AI Assistant

1. **Open AI Chat**:
   - Click the chat bubble icon in the left sidebar
   - Or use keyboard shortcut to open chat

2. **Ask Questions**:
   ```
   How do I connect to a PostgreSQL database in Python?
   ```

3. **Generate Code**:
   ```
   Write a function to parse CSV files and handle missing values
   ```

4. **Explain Code**:
   - Select code in a cell
   - Ask "Explain this code"

### Using LSP Code Completion

1. **Autocomplete**:
   - Start typing and suggestions appear automatically
   - Press `Tab` or `Enter` to accept suggestion
   - Press `Esc` to dismiss

2. **View Documentation**:
   - Hover over any function/class
   - Press `Ctrl` while hovering for more details

3. **Jump to Definition**:
   - `Alt + Click` on any function/variable
   - Or right-click → "Jump to Definition"

4. **Find All References**:
   - Right-click on symbol → "Find References"

### Using Code Formatting

1. **Format Current Cell**:
   - Place cursor in cell
   - Press `Ctrl+Shift+I`

2. **Format Entire Notebook**:
   - Right-click in notebook
   - Select "Format Notebook"

### Using Git Integration

1. **Initialize Repository**:
   - Click Git icon in sidebar
   - Click "Initialize Repository"

2. **Commit Changes**:
   - Stage files (click '+' icon)
   - Write commit message
   - Click "Commit"

3. **Push to Remote**:
   - Click cloud upload icon
   - Enter credentials if needed

## 🔧 Configuration

### Jupyter AI Configuration

Located at: `~/.jupyter/jupyter_jupyter_ai_config.json`

```json
{
  "AiExtension": {
    "model_parameters": {
      "openai-chat:qwen2.5-0.5b": {
        "api_base": "http://litellm:4000/v1",
        "api_key": "unused"
      }
    }
  }
}
```

### LSP Configuration

Located at: `~/.jupyter/lab/user-settings/@jupyter-lsp/jupyterlab-lsp/plugin.jupyterlab-settings`

```json
{
  "continuousHinting": true,
  "suppressContinuousHintingInNotebooks": false,
  "askServerToSendTraceNotifications": true
}
```

### Custom Settings

Access Settings via: Menu → Settings → Settings Editor

**Useful Settings:**
- Theme: Settings → Theme (Light/Dark)
- Font Size: Settings → Text Editor → Font Size
- Autosave: Settings → Document Manager → Autosave Interval
- Code Formatter: Settings → Code Formatter → Format on Save

## 🚀 Advanced Usage

### AI-Assisted Development Workflow

1. **Start with AI Planning**:
   ```
   %%ai openai-chat:qwen2.5-0.5b
   I need to build a data pipeline that reads from PostgreSQL,
   transforms data, and writes to Qdrant. What's the best approach?
   ```

2. **Generate Skeleton Code**:
   - Ask AI to generate the basic structure
   - Use LSP autocomplete to fill in details

3. **Iterate with AI**:
   - Run code and get errors
   - Ask AI: "I'm getting this error: [paste error], how do I fix it?"

4. **Optimize with AI**:
   ```
   %%ai openai-chat:qwen2.5-0.5b
   How can I optimize this code for performance?
   [paste your code]
   ```

5. **Document with AI**:
   ```
   %%ai openai-chat:qwen2.5-0.5b
   Add docstrings and type hints to this function:
   [paste your function]
   ```

### Multi-Language Development

**Python Example**:
```python
%%ai openai-chat:qwen2.5-0.5b
Create a FastAPI endpoint that accepts JSON and stores it in PostgreSQL
```

**JavaScript Example**:
```javascript
// Switch to JavaScript kernel
%%ai openai-chat:qwen2.5-0.5b
Write an Express.js middleware for authentication
```

**Kotlin Example**:
```kotlin
// Switch to Kotlin kernel
%%ai openai-chat:qwen2.5-0.5b
Create a Ktor route handler that processes WebSocket messages
```

## 📚 Extension Documentation

- **Jupyter AI**: https://jupyter-ai.readthedocs.io/
- **JupyterLab LSP**: https://github.com/jupyter-lsp/jupyterlab-lsp
- **JupyterLab Git**: https://github.com/jupyterlab/jupyterlab-git
- **Code Formatter**: https://jupyterlab-code-formatter.readthedocs.io/

## 🎨 Tips & Tricks

### Productivity Shortcuts

- `Ctrl+Shift+C`: Open command palette
- `Ctrl+B`: Toggle left sidebar
- `Shift+Enter`: Run cell and move to next
- `Ctrl+Enter`: Run cell and stay
- `A`: Insert cell above (command mode)
- `B`: Insert cell below (command mode)

### AI Best Practices

1. **Be Specific**: More context = better responses
2. **Iterate**: Refine prompts based on initial output
3. **Use Examples**: Show AI what you want with examples
4. **Verify**: Always review AI-generated code before running

### LSP Best Practices

1. **Let it Index**: Give LSP time to index on first open
2. **Use Hover**: Hover documentation is faster than searching docs
3. **Navigate Efficiently**: Use jump-to-definition instead of searching
4. **Check Diagnostics**: Review the diagnostics panel regularly

## 🐛 Troubleshooting

### AI Chat Not Responding
- Check LiteLLM service is running: `docker ps | grep litellm`
- Verify API endpoint in configuration
- Check network connectivity from notebook container

### LSP Not Working
- Restart JupyterLab kernel
- Check language server installation: `pip list | grep lsp`
- Review LSP logs in browser console (F12)

### Git Authentication Issues
- Use SSH keys for authentication
- Or configure credential helper
- Check GitHub/GitLab token permissions

## 🔄 Updating Extensions

To update all extensions:
```bash
pip install --upgrade jupyter-ai jupyterlab-lsp jupyterlab-git jupyterlab-code-formatter
```

Then rebuild JupyterLab:
```bash
jupyter lab build
```

## 🌟 What Makes This an AI IDE

This isn't just Jupyter with plugins - it's a complete AI-powered development environment:

1. **AI-First Workflow**: LLM assistance integrated at every step
2. **Professional IDE Features**: LSP, Git, formatting like VSCode/IntelliJ
3. **Multi-Language Support**: Python, JavaScript, Kotlin with smart completion
4. **Connected to Your Stack**: LiteLLM integration for custom models
5. **Collaborative**: Git integration for team development
6. **Extensible**: Add more extensions as needed

This transforms JupyterLab from a notebook tool into a full-featured AI-assisted development environment!

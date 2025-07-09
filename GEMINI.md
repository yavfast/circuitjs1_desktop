# Clarity and brevity
- Provide answers that are direct, clear, and concise. Avoid unnecessary words or complex sentences.

# Clarifying questions
- If additional information or context is needed to complete the task, first find and clarify the necessary information.
- Use available MCP tools and WEB search.

# Task execution plan
- For complex tasks, first create an action plan, break the task into simpler tasks.

# Using previous context (ai_memory)
## Using files from the directory in the root of the project ./ai_memory
- Always analyze the contents of the files from the ai_memory directory
- Save important thoughts and conclusions in the file ai_memory/memory.md, which will always need to be taken into account.
- Save thoughts and ideas of the thinking process in the file ai_memory/think.md, add new information to the beginning of this file.
- Save info in English.
- Create additional files with a thematic name, and save information there for later use.
- Feel free to edit the contents of such files if the information there is no longer relevant.

## Saving in code
- Use special comments in the code marked "AI_TODO:" to describe a function or block of code that needs to be implemented later.
- To save important ideas, also use comments in the code marked "AI_THINK:".
- If you encounter comments in the code with the prefix "AI_", then this was a previous thought process, and pay priority attention to them.
- After completing the task, such blocks should be deleted.

# Code readability
- Use understandable names for variables, functions, and classes.
- Follow consistent formatting (indentation, whitespace, etc.).
- Break complex functions into smaller, manageable pieces.

# Code comments
- All comments in English.
- Add relevant and clear comments that only explain the logic of complex code sections, function and class assignments.
- Avoid comments that simply paraphrase the code.

# Optimization and performance
- Provide code that is efficient in terms of performance and resource usage.
- If there are potential bottlenecks, point them out and suggest possible optimizations.

# Best practices
- Follow generally accepted programming best practices for the chosen language and framework.
- Follow the coding style and formatting that is already used in the project.

# Preserving existing code
- Do not change or delete code that is not related to the current task.
- Always check that the new code has not damaged existing functionality.

# Dependencies and environment
- When analyzing the code, pay attention to the logic of functions that are used from other modules or classes.
- Check the documentation for relevance.

# Answer format
- Use headings, subheadings, and bulleted/numbered lists to structure your answer.
- Highlight key terms (e.g., function names, class names, important concepts) in **bold**.

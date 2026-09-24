# Mobile Harness

Mobile Harness runs coding Agents against user-owned Projects and presents their Conversations, Workspace changes, and execution state.

## Workspace

**Project**:
A user-owned workspace rooted in a project directory, containing Conversations and changes made within its Workspace.
_Avoid_: repository when it refers to the user's Project

**Agent**:
A coding assistant selected to work in a Project and treated as the producer of that Project's Agent-specific change history.
_Avoid_: Runtime when it refers to the selected coding assistant

**Change History**:
The record of Workspace changes attributable to one Agent within one Project, retained across Agent Executions and application restarts until the user accepts or undoes it.
_Avoid_: pending changes, Runtime history, session changes

**Change Conflict**:
A condition where a retained Workspace path no longer matches the content last observed by its Agent, preventing undo from overwriting newer changes; acceptance remains available.
_Avoid_: merge error, diff failure

**Agent Execution**:
One Agent turn started from a Conversation and continued until completion, interruption, or failure.
_Avoid_: Runtime session when it refers to the selected turn

**Approval**:
A user decision allowing or denying an Agent Execution's pending tool action.
_Avoid_: permission when it refers to the user's decision about one pending action

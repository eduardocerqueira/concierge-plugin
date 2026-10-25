# Using Concierge

Concierge adds a **Concierge** button to the Jenkins header. It opens `/concierge`, the welcome
page. Everything on it is computed for the person looking at it, from what they are allowed to read.

## Permissions

| Permission | Implied by | Allows |
| --- | --- | --- |
| Concierge/Use | Overall/Read | The welcome page, learning paths and tours. |
| Concierge/ManageTours | Overall/Administer | Editing the welcome message and the "start here" jobs. |
| Concierge/Assistant | Overall/Administer | The AI assistant (phase 2). |

With matrix-based security you can grant Concierge/ManageTours to a team lead who is not an
administrator.

## The welcome page

- **At a glance**: how many jobs you can see, how many are failing, unstable or passing, what is
  building and queued, agents and executors. People with Overall/SystemRead also see how many
  plugins are active and how many have updates.
- **Start here**: the jobs curated for newcomers, with a note on each. Pinned jobs you cannot read
  are left out.
- **Guided tours**: see below.
- **Learning paths**: short lists of steps for people new to Jenkins, developers and DevOps
  engineers. Each step links to a real page on this instance. Steps for pages you cannot open, such
  as the plugin manager for a non-administrator, are left out. Signed-in users can tick steps off;
  progress is stored per user.
- **Failing now**, **Recently built** and **How it is organised** (top-level folders).

## Tours

A tour walks across real pages, highlights the part that matters and explains it in a card. Use
**Next** and **Back**, or **Leave tour** (or Escape) to stop.

| Tour | Pages |
| --- | --- |
| Start here | Each curated job, with its note. Only shown when jobs are pinned. |
| The dashboard | Search, the side panel, the job list, the build queue and executors. |
| A job, a build and its log | A healthy job from this instance, its build history, its last build and its console. |

If a later Jenkins version or another plugin changes a page so that the highlighted part cannot be
found, the step is shown in the middle of the screen instead of being skipped.

Tours keep their place in the browser tab's session storage. Opening an unrelated page ends the
tour.

## Curating the welcome page

People with Concierge/ManageTours see **Edit welcome and start here** on the welcome page.

- **Welcome message**: plain text, up to 2000 characters. Line breaks are kept. Markup is shown as
  text, never rendered.
- **Start here**: up to 12 jobs, by full name (for example `payments/api-build`), each with a note of
  up to 500 characters. The list becomes the "Start here" tour.

One list serves everyone: each person only sees the pinned jobs they can read.

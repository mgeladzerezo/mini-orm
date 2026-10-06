const $ = (id) => document.getElementById(id);

async function getJson(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${url}: ${response.status}`);
  return response.json();
}

function text(tag, content, cls) {
  const el = document.createElement(tag);
  el.textContent = content;
  if (cls) el.className = cls;
  return el;
}

async function refreshPosts() {
  try {
    const posts = await getJson('/api/posts');
    const root = $('posts');
    root.replaceChildren();
    if (posts.length === 0) root.append(text('p', 'No posts yet.'));
    for (const post of posts) {
      const article = document.createElement('article');
      article.append(text('strong', post.title), text('small', ` by ${post.author} (v${post.version})`),
        text('p', post.body));
      for (const comment of post.comments) article.append(text('small', `- ${comment}`), document.createElement('br'));
      root.append(article);
    }
  } catch (e) {
    $('posts').replaceChildren(text('p', e.message, 'error'));
  }
}

async function refreshSide() {
  try {
    const m = await getJson('/api/metrics');
    const dl = $('metrics');
    dl.replaceChildren();
    for (const [key, value] of Object.entries(m)) dl.append(text('dt', key), text('dd', String(value)));
    $('sql').textContent = (await getJson('/api/sql')).join('\n') || 'No statements yet.';
  } catch (e) {
    $('sql').textContent = e.message;
  }
}

$('new-post').addEventListener('submit', async (event) => {
  event.preventDefault();
  const body = new URLSearchParams(new FormData(event.target));
  await fetch('/api/posts', { method: 'POST', body });
  event.target.reset();
  await Promise.all([refreshPosts(), refreshSide()]);
});

refreshPosts();
refreshSide();
setInterval(refreshSide, 2000);

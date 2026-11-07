const drop = document.getElementById('drop');
const input = document.getElementById('file');
const fname = document.getElementById('fname');
const btnHash = document.getElementById('btnHash');
const btnVerify = document.getElementById('btnVerify');
let selected = null;

drop.onclick = () => input.click();
input.onchange = () => pick(input.files[0]);
drop.ondragover = e => { e.preventDefault(); drop.classList.add('over'); };
drop.ondragleave = () => drop.classList.remove('over');
drop.ondrop = e => { e.preventDefault(); drop.classList.remove('over'); pick(e.dataTransfer.files[0]); };

function pick(f) {
  if (!f) return;
  selected = f;
  fname.textContent = f.name + '  (' + f.size + ' bytes)';
  btnHash.disabled = btnVerify.disabled = false;
}

async function call(path) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'X-Filename': encodeURIComponent(selected.name) },
    body: selected
  });
  return res.json();
}

function show(html) {
  document.getElementById('resultCard').hidden = false;
  document.getElementById('result').innerHTML = html;
}

const hashBox = (label, v) => `<div class="label">${label}</div><div class="hash">${v}</div>`;

btnHash.onclick = async () => {
  const d = await call('/api/hash');
  if (d.error) return show(`<span class="badge bad">${d.error}</span>`);
  show(`<span class="badge info">REFERENCE HASH CREATED</span>
        ${hashBox('File', d.file)}${hashBox('SHA-256', d.hash)}`);
  loadRefs();
};

btnVerify.onclick = async () => {
  const d = await call('/api/verify');
  if (d.error) return show(`<span class="badge bad">${d.error}</span>`);
  if (d.status === 'NO_REFERENCE') {
    return show(`<span class="badge warn">NO REFERENCE HASH FOUND - generate one first</span>
                 ${hashBox('Current Hash', d.current)}`);
  }
  const ok = d.status === 'VERIFIED';
  show(`<span class="badge ${ok ? 'ok' : 'bad'}">${ok ? 'INTEGRITY VERIFIED - file has not changed' : 'INTEGRITY CHECK FAILED - file has been modified'}</span>
        ${hashBox('Saved Hash', d.saved)}${hashBox('Current Hash', d.current)}`);
};

async function loadRefs() {
  const list = await (await fetch('/api/references')).json();
  const body = document.getElementById('refs');
  body.innerHTML = list.length
    ? list.map(r => `<tr><td>${r.file}</td><td>${r.hash}</td></tr>`).join('')
    : '<tr><td colspan="2" class="hint">No references yet</td></tr>';
}
loadRefs();
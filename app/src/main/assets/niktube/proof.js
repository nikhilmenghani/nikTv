async function mintProof() {
  let stage = 'homepage';
  try {
    function request(url, body = '', headers = {}) {
      return NikTubeProof.request(url, body, JSON.stringify(headers));
    }
    const html = request('https://www.youtube.com/', '', {'accept-language': 'en-US,en;q=0.7'});
    const configText = html.match(/ytcfg\.set\(({.+?})\);/s)?.[1];
    if (!configText) throw new Error();
    const config = JSON.parse(configText);
    globalThis.yt = {config_: config};
    const visitor = config.VISITOR_DATA || config.INNERTUBE_CONTEXT?.client?.visitorData;
    if (!visitor) throw new Error();
    const playerPath = config.PLAYER_JS_URL || Object.values(config.WEB_PLAYER_CONTEXT_CONFIGS || {}).find(value => value.jsUrl)?.jsUrl;
    stage = 'player';
    if (!playerPath) throw new Error();
    const playerUrl = new URL(playerPath, 'https://www.youtube.com').href;
    const playerScript = request(playerUrl);
    const timestamp = Number(playerScript.match(/signatureTimestamp\s*:\s*(\d+)/)?.[1]);
    if (!timestamp) throw new Error();
    const raw = html.match(/window\.ytAtN\(\s*({[\s\S]*?})\s*\)/)?.[1];
    if (!raw) throw new Error();
    const challenge = NikTubeBG.parseLooseJSON(raw).R.bgChallenge;
    stage = 'challenge';
    const scriptUrl = challenge.interpreterUrl.privateDoNotAccessOrElseTrustedResourceUrlWrappedValue;
    new Function(request(scriptUrl.startsWith('//') ? 'https:' + scriptUrl : scriptUrl))();
    const guard = await NikTubeBG.BotGuardClient.create({program: challenge.program,
      globalName: challenge.globalName, globalObject: globalThis});
    const signals = [];
    const snapshot = await guard.snapshot({webPoSignalOutput: signals});
    stage = 'integrity';
    const response = JSON.parse(request(NikTubeBG.buildURL('GenerateIT', true),
      JSON.stringify(['O43z0dpjhgX20SCx4KAo', snapshot]), NikTubeBG.getHeaders()).replace(/^\)\]\}'[^\n]*\n/, ''));
    stage = 'minter';
    const minter = await NikTubeBG.WebPoMinter.create({integrityToken: response[0],
      estimatedTtlSecs: response[1], mintRefreshThreshold: response[2], websafeFallbackToken: response[3]}, signals);
    const token = await minter.mintAsWebsafeString(NikTubeProof.video());
    const sessionToken = await minter.mintAsWebsafeString(visitor);
    NikTubeProof.success(JSON.stringify({token, sessionToken, visitor, timestamp, playerUrl,
      clientVersion:config.INNERTUBE_CONTEXT.client.clientVersion, ttl: response[1]}));
  } catch (_) { NikTubeProof.failure(stage); }
}

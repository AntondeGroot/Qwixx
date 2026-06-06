// jsdom doesn't implement HTMLMediaElement.play() — mock it so AudioService calls succeed silently.
Object.defineProperty(HTMLMediaElement.prototype, 'play', {
  configurable: true,
  value: () => Promise.resolve(),
});

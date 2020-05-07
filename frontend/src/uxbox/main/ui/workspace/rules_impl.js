const scrollPadding = 50;
const stepSize = 10;
const stepPadding = 20;
const minVal = -50000;
const maxVal = +50000;

function mod (n, d) {
  return (((n % d) + d) % d);
}

export function makeHorizontalTicks(zoom) {
  const path = [];
  const label = [];

  for (let i=minVal; i<maxVal; i+=stepSize) {
    const btm = 100 / zoom;
    const mtm = 50 / zoom;
    const pos = (i * zoom) + scrollPadding ;

    if (mod(i, btm) < stepSize) {
      path.push(`M ${pos} 5 L ${pos} ${stepPadding}`);
      label.push(`<text x="${pos+2}" y="13" fill="#9da2a6" style="font-size: 12px">${i}</text>`);
    } else if (mod(i, mtm) < stepSize) {
      path.push(`M ${pos} 10 L ${pos} ${stepPadding}`);
    } else {
      path.push(`M ${pos} 15 L ${pos} ${stepPadding}`);
    }
  }

  let output = [
    `<path d="${path.join(" ")}" />`,
    label.join(" "),
  ];

  return output.join("");
}

export function makeVerticalTicks(zoom) {
  const path = [];
  const label = [];

  for (let i=minVal; i<maxVal; i+=stepSize) {
    const btm = 100 / zoom;
    const mtm = 50 / zoom;
    const pos = (i * zoom) + scrollPadding;

    if (mod(i, btm) < stepSize) {
      path.push(`M 5 ${pos} L ${stepPadding} ${pos}`);
      label.push(`<text y="${pos-3}" x="5" fill="#9da2a6" style="font-size: 12px" transform="rotate(90 0 ${pos})">${i}</text>`);
    } else if (mod(i, mtm) < stepSize) {
      path.push(`M 10 ${pos} L ${stepPadding} ${pos}`);
    } else {
      path.push(`M 15 ${pos} L ${stepPadding} ${pos}`);
    }
  }

  let output = [
    `<path d="${path.join(" ")}" />`,
    label.join(" "),
  ];

  return output.join("");
}

const R = 40;

const getPositionList = (size) => [
  ...Array.from({ length: size }, (x, i) => {
    const rand = i == 0 ? 0 : Math.random() * 10;
    // const rand = 0;
    const angle = (Math.PI / 180) * (i % 2 ? 64 + rand : 39 + rand);
    const pos =
      (Math.PI / 180) * (((2 * 360) / size) * parseInt(i / 2) + rand * 4 + (i % 2 ? 0 : 10)) +
      29.75;

    return [
      Math.cos(pos) * R * Math.sin(angle),
      R * Math.cos(angle),
      Math.sin(pos) * R * Math.sin(angle),
    ];
  }),
];

export default getPositionList;

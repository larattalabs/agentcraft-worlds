// Campus with 2 wings: see lib/campus.mjs for the layout (ASCII plan) and the generator.
import { buildCampus } from '../lib/campus.mjs';

export const id = 'campus2';
export default () => buildCampus(2);

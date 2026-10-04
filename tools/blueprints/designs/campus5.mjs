// Campus with 5 wings (e.g. a project with five repos): see lib/campus.mjs for the layout (ASCII plan) and the generator.
import { buildCampus } from '../lib/campus.mjs';

export const id = 'campus5';
export default () => buildCampus(5);
